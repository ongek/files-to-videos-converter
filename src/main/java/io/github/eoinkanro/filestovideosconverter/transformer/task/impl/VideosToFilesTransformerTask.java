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

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int OUTPUT_BUFFER_SIZE = 128 * 1024;
    private static final int BULK_ZERO_BUFFER_SIZE = 16384;

    private final byte[] bulkZeroBuffer = new byte[BULK_ZERO_BUFFER_SIZE];
    private final byte[] fileOutputBuffer = new byte[65536]; // 64KB 高速一括フラッシュバッファ
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

            // 【最適化】フィルターを完全撤廃し、HW直結 NV12 Yプレーンを直接処理
            processFileDirectNV12(processData, outputStream);

            // 残余ビットのフラッシュ
            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                appendByteToBuffer(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }

            // バッファに残っているデータをフラッシュ
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

    /**
     * M4 VideoToolbox HWデコーダ直結 (NV12 Yプレーンダイレクトスキャン)
     */
    private void processFileDirectNV12(File video, OutputStream outputStream) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            // M4 ハードウェアデコーダを指定
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

                // Yプレーン(グレースケール輝度)をダイレクトに高速二値化
                decodeYPlane(yPlaneSegment, imageWidth, imageHeight, rowStride, outputStream);

                taskStatistics.poll();
            }
        }
    }

    /**
     * グレースケール輝度 Yプレーンの二値化デコード
     */
    private void decodeYPlane(MemorySegment segment, int width, int height, int rowStride, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;

        // =========================================================================
        // FAST PATH: duplicateFactor == 1 (1ピクセル = 1ビット)
        // =========================================================================
        if (df == 1) {
            for (int y = 0; y < height; y++) {
                long rowAddr = (long) y * rowStride;
                int x = 0;

                // 8ピクセル (8バイト) 単位で一括アンローリング
                for (; x <= width - 8; x += 8) {
                    long addr = rowAddr + x;
                    // Y < 128 なら黒(1)、Y >= 128 なら白(0)
                    // Javaの符号付きbyteにおいて 0〜127(黒寄り)は >= 0、128〜255(白寄り)は < 0
                    int b0 = segment.get(ValueLayout.JAVA_BYTE, addr)     >= 0 ? 1 : 0;
                    int b1 = segment.get(ValueLayout.JAVA_BYTE, addr + 1) >= 0 ? 1 : 0;
                    int b2 = segment.get(ValueLayout.JAVA_BYTE, addr + 2) >= 0 ? 1 : 0;
                    int b3 = segment.get(ValueLayout.JAVA_BYTE, addr + 3) >= 0 ? 1 : 0;
                    int b4 = segment.get(ValueLayout.JAVA_BYTE, addr + 4) >= 0 ? 1 : 0;
                    int b5 = segment.get(ValueLayout.JAVA_BYTE, addr + 5) >= 0 ? 1 : 0;
                    int b6 = segment.get(ValueLayout.JAVA_BYTE, addr + 6) >= 0 ? 1 : 0;
                    int b7 = segment.get(ValueLayout.JAVA_BYTE, addr + 7) >= 0 ? 1 : 0;

                    int byteVal = (b0 << 7) | (b1 << 6) | (b2 << 5) | (b3 << 4)
                                | (b4 << 3) | (b5 << 2) | (b6 << 1) | b7;

                    appendByteToBuffer(byteVal, outputStream);
                }

                // 端数ピクセル処理
                for (; x < width; x++) {
                    int bit = segment.get(ValueLayout.JAVA_BYTE, rowAddr + x) >= 0 ? 1 : 0;
                    processSingleBit(bit, outputStream);
                }
            }
            return;
        }

        // =========================================================================
        // SLOW PATH: duplicateFactor > 1 (耐圧縮ブロック平均化)
        // =========================================================================
        final int blockRows = height / df;
        final int bitsPerRow = width / df;
        final int blockRowStride = df * rowStride;
        // しきい値: ピクセル数 * 128 (Limited Range 16〜235 でも完全中央)
        final int threshold = df * df * 128;

        for (int i = 0; i < blockRows; i++) {
            final long blockStartByte = (long) i * blockRowStride;

            for (int b = 0; b < bitsPerRow; b++) {
                final long colOffset = (long) b * df;
                int ySum = 0;

                long rowAddr = blockStartByte + colOffset;
                for (int r = 0; r < df; r++) {
                    long addr = rowAddr;
                    for (int c = 0; c < df; c++) {
                        // 符号なし 8-bit 輝度値 (0〜255) を加算
                        ySum += (segment.get(ValueLayout.JAVA_BYTE, addr) & 0xFF);
                        addr++;
                    }
                    rowAddr += rowStride;
                }

                // 合計輝度がしきい値未満なら黒(1)、以上なら白(0)
                int bit = (ySum < threshold) ? 1 : 0;
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
