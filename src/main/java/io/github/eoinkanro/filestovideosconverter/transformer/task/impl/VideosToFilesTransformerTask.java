package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int OUTPUT_BUFFER_SIZE = 128 * 1024;
    private static final int BULK_ZERO_BUFFER_SIZE = 16384;
    private static final long SIGN_BIT_MASK_64 = 0x8080808080808080L;

    private final byte[] bulkZeroBuffer = new byte[BULK_ZERO_BUFFER_SIZE];
    private final byte[] fileOutputBuffer = new byte[65536]; // 64KB 高速一括フラッシュバッファ
    private int fileOutputBufferIdx = 0;

    private int[] blockRowAccumulator; // 横方向連続スキャン用ラインバッファ

    private int duplicateFactor;
    private int currentBitsCount;
    private int currentByteVal;
    private long zeroBytesCount;

    public VideosToFilesTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        File resultFile;
        try {
            String currentOriginalFile = fileUtils.getOriginalNameOfFile(processData, inputCLIArgumentsHolder.getArgument(VIDEOS_PATH));
            resultFile = fileUtils.getVideosToFilesResultFile(currentOriginalFile);
        } catch (Exception e) {
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), OUTPUT_BUFFER_SIZE)) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());

            currentBitsCount = 0;
            currentByteVal = 0;
            zeroBytesCount = 0;
            fileOutputBufferIdx = 0;

            // NV12 Yプレーンの超高速デコード
            processFileDirectNV12(processData, outputStream);

            // 残余ビットのフラッシュ
            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                appendByteToBuffer(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }

            flushOutputBuffer(outputStream);

            // ファイル末尾のゼロバイト復元
            int lastZeroBytesCount = fileUtils.getImageLastZeroBytesCount(processData.getAbsolutePath());
            if (lastZeroBytesCount > 0) {
                writeZeroBytesWithCount(lastZeroBytesCount, outputStream);
            }

            outputStream.flush();
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private void processFileDirectNV12(File video, OutputStream outputStream) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setVideoOption("hwaccel", "videotoolbox");
            grabber.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12);
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (frame.image == null || frame.image[0] == null) {
                    continue;
                }

                int imageWidth = frame.imageWidth;
                int imageHeight = frame.imageHeight;
                int rowStride = frame.imageStride;

                ByteBuffer nativeBuffer = (ByteBuffer) frame.image[0];
                MemorySegment yPlaneSegment = MemorySegment.ofBuffer(nativeBuffer);

                decodeYPlaneFast(yPlaneSegment, imageWidth, imageHeight, rowStride, outputStream);

                taskStatistics.poll();
            }
        }
    }

    /**
     * キャッシュライン最適化 ＆ Java 26 ビット抽出による超高速 Y プレーンデコード
     */
    private void decodeYPlaneFast(MemorySegment segment, int width, int height, int rowStride, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;

        // =========================================================================
        // FAST PATH: duplicateFactor == 1 (Java 26 Long.compress による 1 命令抽出)
        // =========================================================================
        if (df == 1) {
            for (int y = 0; y < height; y++) {
                long rowAddr = (long) y * rowStride;
                int x = 0;

                // 8ピクセル (64bit) を一括ロードして 1 命令で 1 バイトへ圧縮
                for (; x <= width - 8; x += 8) {
                    long raw8 = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, rowAddr + x);
                    // 輝度 < 128 (黒=1) の最上位ビットを抽出し、Little-Endian 順序を反転して 1 バイト化
                    long compressedBits = Long.compress(~raw8, SIGN_BIT_MASK_64);
                    int byteVal = Integer.reverse((int) compressedBits) >>> 24;

                    appendByteToBuffer(byteVal, outputStream);
                }

                // 端数ピクセル
                for (; x < width; x++) {
                    int bit = segment.get(ValueLayout.JAVA_BYTE, rowAddr + x) >= 0 ? 1 : 0;
                    processSingleBit(bit, outputStream);
                }
            }
            return;
        }

        // =========================================================================
        // HIGH-SPEED PATH: duplicateFactor > 1 (キャッシュライン横方向連続集約)
        // =========================================================================
        final int bitsPerRow = width / df;
        final int blockRows = height / df;
        final int threshold = df * df * 128; // 耐再エンコード完全中央しきい値

        if (blockRowAccumulator == null || blockRowAccumulator.length < bitsPerRow) {
            blockRowAccumulator = new int[bitsPerRow];
        }

        for (int i = 0; i < blockRows; i++) {
            long blockRowStartAddr = (long) i * df * rowStride;
            Arrays.fill(blockRowAccumulator, 0, bitsPerRow, 0);

            // 【M4 キャッシュ最適化】df 本の行を完全にシーケンシャル（横方向）にスキャン
            for (int r = 0; r < df; r++) {
                long currentRowAddr = blockRowStartAddr + (long) r * rowStride;

                if (df == 4) {
                    // df == 4 特化: 32bit 一括ロードで 4画素をまとめて加算
                    for (int b = 0; b < bitsPerRow; b++) {
                        int raw4 = segment.get(ValueLayout.JAVA_INT_UNALIGNED, currentRowAddr + (b << 2));
                        int sum4 = (raw4 & 0xFF) + ((raw4 >>> 8) & 0xFF) + ((raw4 >>> 16) & 0xFF) + ((raw4 >>> 24) & 0xFF);
                        blockRowAccumulator[b] += sum4;
                    }
                } else {
                    // 汎用 df パス
                    for (int b = 0; b < bitsPerRow; b++) {
                        long addr = currentRowAddr + (long) b * df;
                        int rowSum = 0;
                        for (int c = 0; c < df; c++) {
                            rowSum += (segment.get(ValueLayout.JAVA_BYTE, addr + c) & 0xFF);
                        }
                        blockRowAccumulator[b] += rowSum;
                    }
                }
            }

            // 【8ビット一括パック】1行分のブロックから 8個ずつまとめて 1 バイトを生成 (分岐ゼロ)
            int b = 0;
            for (; b <= bitsPerRow - 8; b += 8) {
                int b0 = blockRowAccumulator[b]     < threshold ? 1 : 0;
                int b1 = blockRowAccumulator[b + 1] < threshold ? 1 : 0;
                int b2 = blockRowAccumulator[b + 2] < threshold ? 1 : 0;
                int b3 = blockRowAccumulator[b + 3] < threshold ? 1 : 0;
                int b4 = blockRowAccumulator[b + 4] < threshold ? 1 : 0;
                int b5 = blockRowAccumulator[b + 5] < threshold ? 1 : 0;
                int b6 = blockRowAccumulator[b + 6] < threshold ? 1 : 0;
                int b7 = blockRowAccumulator[b + 7] < threshold ? 1 : 0;

                int byteVal = (b0 << 7) | (b1 << 6) | (b2 << 5) | (b3 << 4)
                            | (b4 << 3) | (b5 << 2) | (b6 << 1) | b7;

                appendByteToBuffer(byteVal, outputStream);
            }

            // 端数ブロック
            for (; b < bitsPerRow; b++) {
                int bit = blockRowAccumulator[b] < threshold ? 1 : 0;
                processSingleBit(bit, outputStream);
            }
        }
    }

    private void processSingleBit(int bit, OutputStream outputStream) throws IOException {
        currentByteVal = (currentByteVal << 1) | bit;
        if (++currentBitsCount == 8) {
            appendByteToBuffer(currentByteVal, outputStream);
            currentByteVal = 0;
            currentBitsCount = 0;
        }
    }

    private void appendByteToBuffer(int byteVal, OutputStream outputStream) throws IOException {
        if (byteVal == 0) {
            zeroBytesCount++;
            return;
        }
        if (zeroBytesCount > 0) {
            writeZeroBytesWithCount(zeroBytesCount, outputStream);
            zeroBytesCount = 0;
        }

        fileOutputBuffer[fileOutputBufferIdx++] = (byte) byteVal;
        if (fileOutputBufferIdx == fileOutputBuffer.length) {
            flushOutputBuffer(outputStream);
        }
    }

    private void flushOutputBuffer(OutputStream outputStream) throws IOException {
        if (fileOutputBufferIdx > 0) {
            outputStream.write(fileOutputBuffer, 0, fileOutputBufferIdx);
            fileOutputBufferIdx = 0;
        }
    }

    private void writeZeroBytesWithCount(long count, OutputStream outputStream) throws IOException {
        flushOutputBuffer(outputStream);
        long remaining = count;
        final int bufferLength = bulkZeroBuffer.length;

        while (remaining > 0) {
            final int bytesToWrite = (int) Math.min(remaining, bufferLength);
            outputStream.write(bulkZeroBuffer, 0, bytesToWrite);
            remaining -= bytesToWrite;
        }
    }
}
