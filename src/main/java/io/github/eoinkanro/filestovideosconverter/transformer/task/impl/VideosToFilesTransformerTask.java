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
    private byte[] pixelsCache = new byte[0]; // フレームサイズに応じて再利用されるバッファ

    private int duplicateFactor;
    private int imageWidth;

    // ビットストリーム・状態管理用
    private int currentBitsCount;
    private int currentByteVal;
    private long zeroBytesCount;

    private int frameType;

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

        // Output Buffer を 64KB に拡張して I/O コストを削減
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), 65536)) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
            
            // 状態初期化
            currentBitsCount = 0;
            currentByteVal = 0;
            zeroBytesCount = 0;

            processFile(processData, outputStream);

            // 末尾パディングの高速フラッシュ
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

    private void processFile(File video, OutputStream outputStream) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto"); // M4のマルチコアを活用
            grabber.start();
            frameType = grabber.getPixelFormat();

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

            // 配列の再生成を回避し、必要な時だけバッファを拡張
            int requiredPixelsLength = frame.imageHeight * frame.imageWidth * RGB_CHANNELS;
            if (pixelsCache.length < requiredPixelsLength) {
                pixelsCache = new byte[requiredPixelsLength];
            }
            ((ByteBuffer) frame.image[0]).get(pixelsCache, 0, requiredPixelsLength);

            processImage(requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }
    }

    private void processImage(int pixelsLength, OutputStream outputStream) throws IOException {
        int rowStride = imageWidth * RGB_CHANNELS;
        int pixelsIterations = pixelsLength / rowStride / duplicateFactor; // 垂直方向のブロック数
        int bitsPerRow = imageWidth / duplicateFactor;                    // 水平方向のビット数

        for (int i = 0; i < pixelsIterations; i++) {
            int blockStartByte = i * duplicateFactor * rowStride;

            for (int b = 0; b < bitsPerRow; b++) {
                int pixelSum = 0;
                int colByte = b * duplicateFactor * RGB_CHANNELS;

                // duplicateFactor x duplicateFactor のピクセル正方形領域を直接集計
                for (int r = 0; r < duplicateFactor; r++) {
                    int rowByte = blockStartByte + (r * rowStride) + colByte;

                    for (int c = 0; c < duplicateFactor; c++) {
                        int pixelAddr = rowByte + (c * RGB_CHANNELS);

                        if (frameType == AV_PIX_FMT_BGR24) {
                            pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr + 2], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr]);
                        } else {
                            pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr + 2]);
                        }
                    }
                }

                // ビットの決定
                int bit = bytesUtils.pixelToBit(pixelSum, duplicateFactor);

                if (bit >= 0) {
                    currentByteVal = (currentByteVal << 1) | bit;

                    if (++currentBitsCount == 8) {
                        appendByteToStream(currentByteVal, outputStream);
                        currentByteVal = 0;
                        currentBitsCount = 0;
                    }
                }
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
