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

    private byte[] pixelsCache = new byte[0];

    private int duplicateFactor;
    private int imageWidth;

    private int currentBitsCount;
    private int currentByteVal;

    private int frameType;
    
    // 書き込みバイト数の追跡用
    private long totalBytesWritten;

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

        duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
        int lastZeroBytesCount = fileUtils.getImageLastZeroBytesCount(processData.getAbsolutePath());

        // 状態初期化
        currentBitsCount = 0;
        currentByteVal = 0;
        totalBytesWritten = 0;

        // 一旦すべてのデータをテンポラリ出力、または直接書き出し
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), 65536)) {
            
            processFile(processData, outputStream);

            // 残った端数ビットの書き出し
            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                outputStream.write(currentByteVal);
                totalBytesWritten++;
                currentByteVal = 0;
                currentBitsCount = 0;
            }

            outputStream.flush();
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        // === 重要: 動画フレームのあまり（パディング）部分を削る処理 ===
        // デコード結果が「元サイズ + 最終フレームの余白」になっているため、
        // 最後に lastZeroBytesCount (またはフレーム余白) 分を切り詰め (Truncate) ます。
        if (lastZeroBytesCount > 0 && resultFile.exists()) {
            trimFileTail(resultFile, lastZeroBytesCount);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private void processFile(File video, OutputStream outputStream) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");
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
        int pixelsIterations = pixelsLength / rowStride / duplicateFactor;
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

                        if (frameType == AV_PIX_FMT_BGR24) {
                            pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr + 2], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr]);
                        } else {
                            pixelSum += bytesUtils.pixelToBit(pixelsCache[pixelAddr], pixelsCache[pixelAddr + 1], pixelsCache[pixelAddr + 2]);
                        }
                    }
                }

                int bit = bytesUtils.pixelToBit(pixelSum, duplicateFactor);

                if (bit >= 0) {
                    currentByteVal = (currentByteVal << 1) | bit;

                    if (++currentBitsCount == 8) {
                        outputStream.write(currentByteVal);
                        totalBytesWritten++;
                        currentByteVal = 0;
                        currentBitsCount = 0;
                    }
                }
            }
        }
    }

    /**
     * ファイル末尾の不要なパディング（6.6KB分など）を削り落とす
     */
    private void trimFileTail(File file, long bytesToTrim) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long currentLength = raf.length();
            long newLength = Math.max(0, currentLength - bytesToTrim);
            raf.setLength(newLength);
            log.info("Trimmed file {} from {} to {} bytes (removed {} bytes)", file.getName(), currentLength, newLength, bytesToTrim);
        } catch (IOException e) {
            log.error("Failed to trim file tail for {}", file, e);
        }
    }
}
