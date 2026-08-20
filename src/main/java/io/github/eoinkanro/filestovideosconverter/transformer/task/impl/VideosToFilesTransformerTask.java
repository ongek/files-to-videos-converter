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
    private final byte[] fileOutputBuffer = new byte[OUTPUT_BUFFER_SIZE]; // 128KB 高速一括フラッシュバッファ
    private int fileOutputBufferIdx = 0;

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
                int rowStride = frame.imageStride; // 128B アライメントストライド

                ByteBuffer nativeBuffer = (ByteBuffer) frame.image[0];
                MemorySegment yPlaneSegment = MemorySegment.ofBuffer(nativeBuffer);

                decodeYPlaneBlitz(yPlaneSegment, imageWidth, imageHeight, rowStride, outputStream);

                taskStatistics.poll();
            }
        }
    }

    /**
     * 【M4 Blitz Decoder】
     * 中間配列を完全全廃し、レジスタ内で 4行を同時合算して 1 バイトを生成する極限デコーダ
     */
    private void decodeYPlaneBlitz(MemorySegment segment, int width, int height, int rowStride, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;

        // =========================================================================
        // 【FAST PATH: df == 1】Java 26 Long.compress による 1 命令抽出
        // =========================================================================
        if (df == 1) {
            for (int y = 0; y < height; y++) {
                long rowAddr = (long) y * rowStride;
                int x = 0;

                for (; x <= width - 8; x += 8) {
                    long raw8 = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, rowAddr + x);
                    long compressedBits = Long.compress(~raw8, SIGN_BIT_MASK_64);
                    int byteVal = Integer.reverse((int) compressedBits) >>> 24;

                    appendByteToBuffer(byteVal, outputStream);
                }

                for (; x < width; x++) {
                    int bit = segment.get(ValueLayout.JAVA_BYTE, rowAddr + x) >= 0 ? 1 : 0;
                    processSingleBit(bit, outputStream);
                }
            }
            return;
        }

        // =========================================================================
        // 【M4 ULTRA FAST PATH: df == 4】
        // 中間配列ゼロ！4行の 32バイト(8ブロック)をレジスタで一発合算して 1バイト直書き
        // =========================================================================
        if (df == 4) {
            final int blockRows = height >> 2;   // / 4
            final int blockRowStride = rowStride << 2; // * 4
            final int threshold = 4 * 4 * 128; // 2048 (耐再エンコード完全中央しきい値)

            for (int i = 0; i < blockRows; i++) {
                long addr0 = (long) i * blockRowStride;
                long addr1 = addr0 + rowStride;
                long addr2 = addr1 + rowStride;
                long addr3 = addr2 + rowStride;

                int x = 0;
                // 8ブロック (横 32 ピクセル) ごとに 1 バイトを一撃生成
                for (; x <= width - 32; x += 32) {
                    // ブロック 0〜7 の 16ピクセル(4x4)をレジスタ内で完全並列合算
                    int s0 = sum4(segment, addr0 + x)      + sum4(segment, addr1 + x)      + sum4(segment, addr2 + x)      + sum4(segment, addr3 + x);
                    int s1 = sum4(segment, addr0 + x + 4)  + sum4(segment, addr1 + x + 4)  + sum4(segment, addr2 + x + 4)  + sum4(segment, addr3 + x + 4);
                    int s2 = sum4(segment, addr0 + x + 8)  + sum4(segment, addr1 + x + 8)  + sum4(segment, addr2 + x + 8)  + sum4(segment, addr3 + x + 8);
                    int s3 = sum4(segment, addr0 + x + 12) + sum4(segment, addr1 + x + 12) + sum4(segment, addr2 + x + 12) + sum4(segment, addr3 + x + 12);
                    int s4 = sum4(segment, addr0 + x + 16) + sum4(segment, addr1 + x + 16) + sum4(segment, addr2 + x + 16) + sum4(segment, addr3 + x + 16);
                    int s5 = sum4(segment, addr0 + x + 20) + sum4(segment, addr1 + x + 20) + sum4(segment, addr2 + x + 20) + sum4(segment, addr3 + x + 20);
                    int s6 = sum4(segment, addr0 + x + 24) + sum4(segment, addr1 + x + 24) + sum4(segment, addr2 + x + 24) + sum4(segment, addr3 + x + 24);
                    int s7 = sum4(segment, addr0 + x + 28) + sum4(segment, addr1 + x + 28) + sum4(segment, addr2 + x + 28) + sum4(segment, addr3 + x + 28);

                    // AArch64 CSET による完全 Branchless バイト合成
                    int byteVal = ((s0 < threshold ? 1 : 0) << 7)
                                | ((s1 < threshold ? 1 : 0) << 6)
                                | ((s2 < threshold ? 1 : 0) << 5)
                                | ((s3 < threshold ? 1 : 0) << 4)
                                | ((s4 < threshold ? 1 : 0) << 3)
                                | ((s5 < threshold ? 1 : 0) << 2)
                                | ((s6 < threshold ? 1 : 0) << 1)
                                |  (s7 < threshold ? 1 : 0);

                    appendByteToBuffer(byteVal, outputStream);
                }

                // 端数ブロック (存在する場合)
                for (; x < width; x += 4) {
                    int s = sum4(segment, addr0 + x) + sum4(segment, addr1 + x) + sum4(segment, addr2 + x) + sum4(segment, addr3 + x);
                    processSingleBit(s < threshold ? 1 : 0, outputStream);
                }
            }
            return;
        }

        // =========================================================================
        // 【汎用 PATH: 任意の df】
        // =========================================================================
        final int bitsPerRow = width / df;
        final int blockRows = height / df;
        final int blockRowStride = df * rowStride;
        final int threshold = df * df * 128;

        for (int i = 0; i < blockRows; i++) {
            long blockRowStartAddr = (long) i * blockRowStride;

            for (int b = 0; b < bitsPerRow; b++) {
                long colOffset = (long) b * df;
                int ySum = 0;

                for (int r = 0; r < df; r++) {
                    long rowAddr = blockRowStartAddr + (long) r * rowStride + colOffset;
                    for (int c = 0; c < df; c++) {
                        ySum += (segment.get(ValueLayout.JAVA_BYTE, rowAddr + c) & 0xFF);
                    }
                }

                processSingleBit(ySum < threshold ? 1 : 0, outputStream);
            }
        }
    }

    /**
     * 【M4 SWAR 4画素加算】32-bit (4バイト) を一括ロードして 4画素の輝度を瞬時に合算
     */
    private static int sum4(MemorySegment segment, long addr) {
        int raw4 = segment.get(ValueLayout.JAVA_INT_UNALIGNED, addr);
        return (raw4 & 0xFF) + ((raw4 >>> 8) & 0xFF) + ((raw4 >>> 16) & 0xFF) + ((raw4 >>> 24) & 0xFF);
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
