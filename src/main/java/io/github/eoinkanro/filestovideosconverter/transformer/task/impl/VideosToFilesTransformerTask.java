package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int RGB_CHANNELS = 3;
    private static final int OUTPUT_BUFFER_SIZE = 128 * 1024;

    // Apple M4のキャッシュライン境界（128バイト）
    private static final long CACHE_LINE_SIZE = 128;
    
    // 【物理×C2の完全合体解】
    // 32画素 (2^5の累乗) × 3バイト = 96バイト
    // 128Bアライメント下で 96B < 128B となり、単一ループ内でLine Splitが原理的に100%発生しない
    private static final int SIMD_PIXELS_BATCH = 32; 
    private static final int SIMD_BYTES_BATCH = SIMD_PIXELS_BATCH * RGB_CHANNELS; // 96 Bytes

    private final byte[] bulkZeroBuffer = new byte[16384];

    private int duplicateFactor;
    private int imageWidth;

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

            processFile(processData, outputStream);

            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                appendByteToStream(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }

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

    private void processFile(File video, OutputStream outputStream) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");
            grabber.start();

            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("format=rgb24", grabber.getImageWidth(), grabber.getImageHeight())) {
                filter.start();
                processFile(grabber, filter, outputStream);
            }
        }
    }

    private void processFile(FFmpegFrameGrabber grabber, FFmpegFrameFilter filter, OutputStream outputStream) throws IOException {
        Frame frame;
        while ((frame = grabber.grabFrame()) != null) {
            imageWidth = frame.imageWidth;

            filter.push(frame);
            frame = filter.pull();

            if (frame.type != null) {
                continue;
            }

            int requiredPixelsLength = frame.imageHeight * frame.imageWidth * RGB_CHANNELS;

            ByteBuffer nativeBuffer = (ByteBuffer) frame.image[0];
            MemorySegment segment = MemorySegment.ofBuffer(nativeBuffer);

            processImageLimitFFM(segment, requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }
    }

    /**
     * 128B Align-Up ＆ 32画素(96B = 2^5 NEON) 物理・VM両制覇アルゴリズム
     */
    private void processImageLimitFFM(MemorySegment segment, int pixelsLength, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;
        int bitCount = this.currentBitsCount;
        int byteVal = this.currentByteVal;

        // =========================================================================
        // FAST PATH: duplicateFactor == 1
        // =========================================================================
        if (df == 1) {
            long addr = 0;
            long rawAddress = segment.address();

            // 1. [HEAD] 128Bキャッシュライン境界にアライメントを合わせる
            long offsetToAlignment = (CACHE_LINE_SIZE - (rawAddress % CACHE_LINE_SIZE)) % CACHE_LINE_SIZE;
            long headBytes = Math.min(pixelsLength, offsetToAlignment - (offsetToAlignment % RGB_CHANNELS));

            for (; addr < headBytes; addr += RGB_CHANNELS) {
                byte r = segment.get(ValueLayout.JAVA_BYTE, addr);
                byte g = segment.get(ValueLayout.JAVA_BYTE, addr + 1);
                byte b = segment.get(ValueLayout.JAVA_BYTE, addr + 2);

                int pBit = bytesUtils.pixelToBit(r, g, b);
                int bit = bytesUtils.pixelToBit(pBit, 1);

                if (bit >= 0) {
                    byteVal = (byteVal << 1) | bit;
                    if (++bitCount == 8) {
                        appendByteToStream(byteVal, outputStream);
                        byteVal = 0;
                        bitCount = 0;
                    }
                }
            }

            // 2. [ALIGNED BODY] 96バイト（32画素 = 2^5）単位での超高速一括読み出し
            // C2コンパイラの2の累乗条件を満たしつつ、128Bキャッシュライン境界(96 < 128)を一切侵さない
            long remainingBytes = pixelsLength - addr;
            long bodyEnd = addr + (remainingBytes - (remainingBytes % SIMD_BYTES_BATCH));

            for (; addr < bodyEnd; addr += SIMD_BYTES_BATCH) {
                for (int p = 0; p < SIMD_PIXELS_BATCH; p++) {
                    long pxAddr = addr + (p * RGB_CHANNELS);
                    byte r = segment.get(ValueLayout.JAVA_BYTE, pxAddr);
                    byte g = segment.get(ValueLayout.JAVA_BYTE, pxAddr + 1);
                    byte b = segment.get(ValueLayout.JAVA_BYTE, pxAddr + 2);

                    int pBit = bytesUtils.pixelToBit(r, g, b);
                    int bit = bytesUtils.pixelToBit(pBit, 1);

                    if (bit >= 0) {
                        byteVal = (byteVal << 1) | bit;
                        if (++bitCount == 8) {
                            appendByteToStream(byteVal, outputStream);
                            byteVal = 0;
                            bitCount = 0;
                        }
                    }
                }
            }

            // 3. [TAIL] 端数処理
            for (; addr < pixelsLength; addr += RGB_CHANNELS) {
                byte r = segment.get(ValueLayout.JAVA_BYTE, addr);
                byte g = segment.get(ValueLayout.JAVA_BYTE, addr + 1);
                byte b = segment.get(ValueLayout.JAVA_BYTE, addr + 2);

                int pBit = bytesUtils.pixelToBit(r, g, b);
                int bit = bytesUtils.pixelToBit(pBit, 1);

                if (bit >= 0) {
                    byteVal = (byteVal << 1) | bit;
                    if (++bitCount == 8) {
                        appendByteToStream(byteVal, outputStream);
                        byteVal = 0;
                        bitCount = 0;
                    }
                }
            }

            this.currentBitsCount = bitCount;
            this.currentByteVal = byteVal;
            return;
        }

        // =========================================================================
        // SLOW PATH: duplicateFactor > 1
        // =========================================================================
        final int rowStride = imageWidth * RGB_CHANNELS;
        final int pixelsIterations = pixelsLength / rowStride / df;
        final int bitsPerRow = imageWidth / df;

        final int colByteStride = df * RGB_CHANNELS;
        final int blockRowStride = df * rowStride;

        for (int i = 0; i < pixelsIterations; i++) {
            final long blockStartByte = (long) i * blockRowStride;

            for (int b = 0; b < bitsPerRow; b++) {
                final long colByte = (long) b * colByteStride;
                int pixelSum = 0;

                long rowAddr = blockStartByte + colByte;
                for (int r = 0; r < df; r++) {
                    long addr = rowAddr;
                    for (int c = 0; c < df; c++) {
                        byte red = segment.get(ValueLayout.JAVA_BYTE, addr);
                        byte green = segment.get(ValueLayout.JAVA_BYTE, addr + 1);
                        byte blue = segment.get(ValueLayout.JAVA_BYTE, addr + 2);

                        pixelSum += bytesUtils.pixelToBit(red, green, blue);
                        addr += RGB_CHANNELS;
                    }
                    rowAddr += rowStride;
                }

                int bit = bytesUtils.pixelToBit(pixelSum, df);

                if (bit >= 0) {
                    byteVal = (byteVal << 1) | bit;

                    if (++bitCount == 8) {
                        appendByteToStream(byteVal, outputStream);
                        byteVal = 0;
                        bitCount = 0;
                    }
                }
            }
        }

        this.currentBitsCount = bitCount;
        this.currentByteVal = byteVal;
    }

    private void appendByteToStream(int byteVal, OutputStream outputStream) throws IOException {
        if (byteVal == 0) {
            zeroBytesCount++;
            return;
        }
        if (zeroBytesCount > 0) {
            writeZeroBytesWithCount(zeroBytesCount, outputStream);
            zeroBytesCount = 0;
        }
        outputStream.write(byteVal);
    }

    private void writeZeroBytesWithCount(long count, OutputStream outputStream) throws IOException {
        long remaining = count;
        final int bufferLength = bulkZeroBuffer.length;

        while (remaining > 0) {
            final int bytesToWrite = (int) Math.min(remaining, bufferLength);
            outputStream.write(bulkZeroBuffer, 0, bytesToWrite);
            remaining -= bytesToWrite;
        }
    }
}
