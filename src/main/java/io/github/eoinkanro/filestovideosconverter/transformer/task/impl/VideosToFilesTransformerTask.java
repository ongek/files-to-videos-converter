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
    
    private static final int UNROLL_PIXELS = 32;
    private static final int UNROLL_BYTES = UNROLL_PIXELS * RGB_CHANNELS; // 96 Bytes

    private final byte[] bulkZeroBuffer = new byte[16384];
    private final int[] extractedBitsBuffer = new int[UNROLL_PIXELS];

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
        int codecId = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_NONE;

        // 1. 【安全第一】メタデータ取得専用の別グラバーを一瞬だけ起動してコーデックを盗む
        try (FFmpegFrameGrabber metaGrabber = new FFmpegFrameGrabber(video)) {
            metaGrabber.start();
            codecId = metaGrabber.getVideoCodec();
            metaGrabber.stop(); // 瞬時に閉じる
        } catch (Exception e) {
            log.warn("Failed to pre-scan codec, falling back to CPU decoding: {}", e.getMessage());
        }

        // 2. 本番のデコード用グラバーを起動
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");

            // 3. 盗み出したコーデックIDを元に、M4メディアエンジンを動的に撃ち分ける
            if (codecId == org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264) {
                grabber.setVideoCodecName("h264_videotoolbox"); // H.264用M4回路
                grabber.setOption("hwaccel", "videotoolbox");
            } else if (codecId == org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC) {
                grabber.setVideoCodecName("hevc_videotoolbox"); // HEVC用M4回路
                grabber.setOption("hwaccel", "videotoolbox");
            }
            // 💡 ログにある「mpeg4」などの場合は、上記をスルーして安全にCPUデコード（デフォルト）に流れる

            grabber.start(); // 本番開始（1回しか呼ばないので絶対にクラッシュしない）

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

            int rowStride = frame.imageStride;
            ByteBuffer nativeBuffer = (ByteBuffer) frame.image[0];
            MemorySegment segment = MemorySegment.ofBuffer(nativeBuffer);

            processImageLimitFFM(segment, frame.imageHeight, rowStride, outputStream);

            taskStatistics.poll();
        }
    }

    private void processImageLimitFFM(MemorySegment segment, int imageHeight, int rowStride, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;
        final int width = imageWidth;

        // FAST PATH: Row-based Cache-Aligned Iteration (df == 1)
        if (df == 1) {
            final int activeRowBytes = width * RGB_CHANNELS;
            final int unrollLimitBytes = activeRowBytes - (activeRowBytes % UNROLL_BYTES);

            for (int y = 0; y < imageHeight; y++) {
                final long rowBaseAddr = (long) y * rowStride;
                long addr = rowBaseAddr;
                final long unrollEndAddr = rowBaseAddr + unrollLimitBytes;

                for (; addr < unrollEndAddr; addr += UNROLL_BYTES) {
                    for (int p = 0; p < UNROLL_PIXELS; p++) {
                        long pxAddr = addr + (p * RGB_CHANNELS);
                        byte r = segment.get(ValueLayout.JAVA_BYTE, pxAddr);
                        byte g = segment.get(ValueLayout.JAVA_BYTE, pxAddr + 1);
                        byte b = segment.get(ValueLayout.JAVA_BYTE, pxAddr + 2);

                        int pBit = bytesUtils.pixelToBit(r, g, b);
                        extractedBitsBuffer[p] = bytesUtils.pixelToBit(pBit, 1);
                    }

                    for (int p = 0; p < UNROLL_PIXELS; p++) {
                        processSingleBit(extractedBitsBuffer[p], outputStream);
                    }
                }

                final long activeEndAddr = rowBaseAddr + activeRowBytes;
                for (; addr < activeEndAddr; addr += RGB_CHANNELS) {
                    byte r = segment.get(ValueLayout.JAVA_BYTE, addr);
                    byte g = segment.get(ValueLayout.JAVA_BYTE, addr + 1);
                    byte b = segment.get(ValueLayout.JAVA_BYTE, addr + 2);

                    int pBit = bytesUtils.pixelToBit(r, g, b);
                    int bit = bytesUtils.pixelToBit(pBit, 1);
                    processSingleBit(bit, outputStream);
                }
            }
            return;
        }

        // SLOW PATH: Block-based Iteration (df > 1)
        final int blockRows = imageHeight / df;
        final int bitsPerRow = width / df;

        final int colByteStride = df * RGB_CHANNELS;
        final int blockRowStride = df * rowStride;

        for (int i = 0; i < blockRows; i++) {
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
                processSingleBit(bit, outputStream);
            }
        }
    }

    private void processSingleBit(int bit, OutputStream outputStream) throws IOException {
        if (bit >= 0) {
            currentByteVal = (currentByteVal << 1) | bit;

            if (++currentBitsCount == 8) {
                appendByteToStream(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }
        }
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
