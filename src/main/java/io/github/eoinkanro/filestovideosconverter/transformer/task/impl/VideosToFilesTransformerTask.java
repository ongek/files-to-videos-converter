package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.nio.ByteBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int RGB_CHANNELS = 3;

    // === ゼロアロケーション用キャッシュバッファ ===
    private final byte[] bulkZeroBuffer = new byte[16384];
    private byte[] pixelsCache = new byte[0]; // フレームサイズに応じて使い回すバッファ

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

        // 64KBバッファでファイル出力I/Oを高速化
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), 65536)) {
            int duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());

            processFile(processData, duplicateFactor, outputStream);

            // 末尾パディングの書き出し
            int lastZeroBytesCount = fileUtils.getImageLastZeroBytesCount(processData.getAbsolutePath());
            if (lastZeroBytesCount > 0) {
                writeZeroBytesWithCount(lastZeroBytesCount, outputStream);
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private void processFile(File video, int duplicateFactor, OutputStream outputStream) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");
            grabber.start();

            // FFmpegFrameFilter により色空間を確実に RGB24 へ統一
            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("format=rgb24", grabber.getImageWidth(), grabber.getImageHeight())) {
                filter.start();

                int currentByteVal = 0;
                int currentBitsCount = 0;
                long zeroBytesCount = 0;

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    filter.push(frame);
                    Frame filteredFrame = filter.pull();

                    if (filteredFrame == null || filteredFrame.image == null || filteredFrame.image.length == 0) {
                        continue;
                    }

                    int imageWidth = filteredFrame.imageWidth;
                    int imageHeight = filteredFrame.imageHeight;
                    int requiredPixelsLength = imageHeight * imageWidth * RGB_CHANNELS;

                    // ヒープアロケーションの全廃（既存配列の再利用）
                    if (pixelsCache.length < requiredPixelsLength) {
                        pixelsCache = new byte[requiredPixelsLength];
                    }
                    ((ByteBuffer) filteredFrame.image[0]).get(pixelsCache, 0, requiredPixelsLength);

                    int frameType = grabber.getPixelFormat();
                    int rowStride = imageWidth * RGB_CHANNELS;
                    int pixelsIterations = imageHeight / duplicateFactor;
                    int bitsPerRow = imageWidth / duplicateFactor;

                    for (int i = 0; i < pixelsIterations; i++) {
                        int blockStartByte = i * duplicateFactor * rowStride;

                        for (int b = 0; b < bitsPerRow; b++) {
                            int pixelSum = 0;
                            int colByte = b * duplicateFactor * RGB_CHANNELS;

                            for (int r = 0; r < duplicateFactor; r++) {
                                int rowByte = blockStartByte + (r * rowStride) + colByte;

                                for (int c = 0; c < duplicateFactor; c++) {
                                    int pixelAddr = rowByte + (c * RGB_CHANNELS);

                                    // 本来の bytesUtils.pixelToBit ロジックを正確に呼び出し
                                    if (frameType == AV_PIX_FMT_BGR24) {
                                        pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr + 2], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr]);
                                    } else {
                                        pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr + 2]);
                                    }
                                }
                            }

                            int bit = bytesUtils.pixelToBit(pixelSum, duplicateFactor);

                            if (bit >= 0) {
                                // 高速ビットシフト演算
                                currentByteVal = (currentByteVal << 1) | bit;

                                if (++currentBitsCount == 8) {
                                    if (currentByteVal == 0) {
                                        zeroBytesCount++;
                                    } else {
                                        if (zeroBytesCount > 0) {
                                            writeZeroBytesWithCount(zeroBytesCount, outputStream);
                                            zeroBytesCount = 0;
                                        }
                                        outputStream.write(currentByteVal);
                                    }
                                    currentByteVal = 0;
                                    currentBitsCount = 0;
                                }
                            }
                        }
                    }
                    taskStatistics.poll();
                }

                // 残りビットの書き出し
                if (currentBitsCount > 0) {
                    int finalByte = currentByteVal << (8 - currentBitsCount);
                    if (finalByte == 0) {
                        zeroBytesCount++;
                    } else {
                        if (zeroBytesCount > 0) {
                            writeZeroBytesWithCount(zeroBytesCount, outputStream);
                            zeroBytesCount = 0;
                        }
                        outputStream.write(finalByte);
                    }
                }
                if (zeroBytesCount > 0) {
                    writeZeroBytesWithCount(zeroBytesCount, outputStream);
                }
            }
        }
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
