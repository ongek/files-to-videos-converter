package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.nio.ByteBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

public class VideosToFilesTransformerTask extends TransformerTask {

    private static final Logger log = LogManager.getLogger(VideosToFilesTransformerTask.class);

    private static final int RGB_CHANNELS = 3;
    private static final byte[] ZERO_BUFFER = new byte[8192];

    private int duplicateFactor;
    private int imageWidth;
    private int imageHeight;

    private byte bitBuffer;
    private int bitCount;
    private long zeroBytesCount;

    private byte[] pixelBuffer;

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

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile))) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
            processFile(processData, outputStream);

            int lastZeroBytesCount = fileUtils.getImageLastZeroBytesCount(processData.getAbsolutePath());
            writeZeroBytes(lastZeroBytesCount, outputStream);
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private void processFile(File video, OutputStream outputStream) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.start();
            imageWidth = grabber.getImageWidth();
            imageHeight = grabber.getImageHeight();

            pixelBuffer = new byte[imageHeight * imageWidth * RGB_CHANNELS];

            // フィルターで一貫してプレーンなrgb24形式に統一
            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("format=rgb24", imageWidth, imageHeight)) {
                filter.start();
                processFrames(grabber, filter, outputStream);
            }
        }
    }

    private void processFrames(FFmpegFrameGrabber grabber, FFmpegFrameFilter filter, OutputStream outputStream) throws IOException {
        Frame frame;
        clearContextTempVariables();

        while ((frame = grabber.grabFrame()) != null) {
            filter.push(frame);
            Frame filteredFrame = filter.pull();

            if (filteredFrame.type != null) {
                continue;
            }

            if (filteredFrame.image[0] instanceof ByteBuffer byteBuffer) {
                byteBuffer.get(pixelBuffer, 0, pixelBuffer.length);
            }

            processImage(outputStream);
            taskStatistics.poll();
        }
    }

    private void processImage(OutputStream outputStream) throws IOException {
        int rowStride = imageWidth * RGB_CHANNELS;
        int chunkHeight = duplicateFactor;
        int totalChunks = imageHeight / chunkHeight;

        for (int chunk = 0; chunk < totalChunks; chunk++) {
            int startRow = chunk * chunkHeight;

            for (int col = 0; col < imageWidth; col += duplicateFactor) {
                long totalR = 0;
                long totalG = 0;
                long totalB = 0;

                // ブロック内の全ピクセルのRGB純粋値を正しく集計
                for (int r = 0; r < chunkHeight; r++) {
                    int rowOffset = (startRow + r) * rowStride;
                    for (int c = 0; c < duplicateFactor; c++) {
                        int pixelIndex = rowOffset + (col + c) * RGB_CHANNELS;

                        totalR += (pixelBuffer[pixelIndex] & 0xFF);
                        totalG += (pixelBuffer[pixelIndex + 1] & 0xFF);
                        totalB += (pixelBuffer[pixelIndex + 2] & 0xFF);
                    }
                }

                // 1ピクセルあたりの平均RGBから、元のBytesUtils仕様に沿った標準int画素(ARGB)を合成
                long blockPixels = (long) duplicateFactor * duplicateFactor;
                int avgR = (int) (totalR / blockPixels);
                int avgG = (int) (totalG / blockPixels);
                int avgB = (int) (totalB / blockPixels);

                // BytesUtils.pixelToBit(byte, byte, byte) の本来の符号なし結合処理を完全再現
                int combinedPixel = (0xFF << 24) | (avgR << 16) | (avgG << 8) | avgB;

                // 結合された正確な画素 int 値を、元の単一ピクセル用判定ロジックへ通す
                int bit = bytesUtils.pixelToBit(combinedPixel, 1); 
                
                if (bit >= 0) {
                    bitBuffer = (byte) ((bitBuffer << 1) | bit);
                    bitCount++;

                    if (bitCount == 8) {
                        writeParsedByte(bitBuffer, outputStream);
                        bitBuffer = 0;
                        bitCount = 0;
                    }
                }
            }
        }
    }

    private void writeParsedByte(byte aByte, OutputStream outputStream) throws IOException {
        if (aByte == 0) {
            zeroBytesCount++;
        } else {
            if (zeroBytesCount > 0) {
                writeZeroBytes(zeroBytesCount, outputStream);
                zeroBytesCount = 0;
            }
            outputStream.write(aByte);
        }
    }

    private void clearContextTempVariables() {
        bitBuffer = 0;
        bitCount = 0;
        zeroBytesCount = 0;
    }

    private void writeZeroBytes(long count, OutputStream outputStream) throws IOException {
        while (count > 0) {
            int chunk = (int) Math.min(count, ZERO_BUFFER.length);
            outputStream.write(ZERO_BUFFER, 0, chunk);
            count -= chunk;
        }
    }
}
