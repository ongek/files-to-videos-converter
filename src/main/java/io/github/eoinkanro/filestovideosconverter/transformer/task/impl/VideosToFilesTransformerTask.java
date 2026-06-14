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
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

public class VideosToFilesTransformerTask extends TransformerTask {

    // Lombokの機嫌に左右されない、確実なロガー定義
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
    private int frameType;

    public VideosToFilesTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        
        // 元の実装の統計情報のセット方法に従う
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
            frameType = grabber.getPixelFormat();
            imageWidth = grabber.getImageWidth();
            imageHeight = grabber.getImageHeight();

            // GCを一切発生させないための固定バッファ
            pixelBuffer = new byte[imageHeight * imageWidth * RGB_CHANNELS];

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

        boolean isBgr = (frameType == AV_PIX_FMT_BGR24);

        for (int chunk = 0; chunk < totalChunks; chunk++) {
            int startRow = chunk * chunkHeight;

            for (int col = 0; col < imageWidth; col += duplicateFactor) {
                long pixelSum = 0;

                for (int r = 0; r < chunkHeight; r++) {
                    int rowOffset = (startRow + r) * rowStride;
                    for (int c = 0; c < duplicateFactor; c++) {
                        int pixelIndex = rowOffset + (col + c) * RGB_CHANNELS;

                        byte b1 = pixelBuffer[pixelIndex];
                        byte b2 = pixelBuffer[pixelIndex + 1];
                        byte b3 = pixelBuffer[pixelIndex + 2];

                        // BytesUtils.pixelToBit(byte, byte, byte) が行っていたARGB合成処理をインラインで高速展開
                        // 符号拡張 (0xFF & b) を忘れると意図しない負数になるため適切にマスク処理
                        int argb;
                        if (isBgr) {
                            argb = (0xFF << 24) | ((b3 & 0xFF) << 16) | ((b2 & 0xFF) << 8) | (b1 & 0xFF);
                        } else {
                            argb = (0xFF << 24) | ((b1 & 0xFF) << 16) | ((b2 & 0xFF) << 8) | (b3 & 0xFF);
                        }
                        
                        pixelSum += argb;
                    }
                }

                // 合計されたARGB画素の値を集計して1つのビットに変換
                int bit = bytesUtils.pixelToBit(pixelSum, duplicateFactor);
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
