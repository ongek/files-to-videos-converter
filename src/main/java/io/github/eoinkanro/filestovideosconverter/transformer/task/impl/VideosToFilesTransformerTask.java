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

    // === 【Snow Leopard 4.6】ゼロアロケーション・再利用プリミティブ領域 ===
    private final byte[] bulkZeroBuffer = new byte[16384]; 
    private byte[] pixelsCache = new byte[0]; // フレームサイズに応じて再利用されるキャッシュバッファ
    private int[] bitsRowCache = new int[0];   // ループ内での配列生成を排除するキャッシュ

    private int duplicateFactor;
    private int imageWidth;

    // ビット演算・ストリーム制御用
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

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile))) {
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
        try(FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");
            grabber.start();
            frameType = grabber.getPixelFormat();

            try(FFmpegFrameFilter filter = new FFmpegFrameFilter("format=rgb24", grabber.getImageWidth(), grabber.getImageHeight())) {
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

            // 毎フレームの new byte[] を廃止し、既存配列を使い回す
            int requiredPixelsLength = frame.imageHeight * frame.imageWidth * RGB_CHANNELS;
            if (pixelsCache.length < requiredPixelsLength) {
                pixelsCache = new byte[requiredPixelsLength];
            }
            ((ByteBuffer) frame.image[0]).get(pixelsCache, 0, requiredPixelsLength);

            processImage(requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }

        // 未フラッシュの残余ビットがあれば、オリジナルと同様に処理
        if (currentBitsCount > 0) {
            int finalByte = currentByteVal << (8 - currentBitsCount);
            appendByteToStream(finalByte, outputStream);
        }
        if (zeroBytesCount > 0) {
            writeZeroBytesWithCount(zeroBytesCount, outputStream);
        }
    }

    private void processImage(int pixelsLength, OutputStream outputStream) throws IOException {
        int pixelsIterations = pixelsLength / imageWidth / RGB_CHANNELS / duplicateFactor;
        int pixelsLastIndex = 0;

        for (int i = 0; i < pixelsIterations; i++) {
            int requiredBitsRowLength = imageWidth / duplicateFactor;
            if (bitsRowCache.length < requiredBitsRowLength) {
                bitsRowCache = new int[requiredBitsRowLength];
            }

            // オリジナルの transformToBitRow の完全等価展開（インライン化）
            int pixelSum = 0;
            int duplicateFactorIterations = 0;
            int resultIndex = 0;
            int currentIndex = 0;
            int rowLength = imageWidth * RGB_CHANNELS;

            while (currentIndex < rowLength) {
                if (duplicateFactorIterations >= duplicateFactor) {
                    bitsRowCache[resultIndex] = bytesUtils.pixelToBit(pixelSum, duplicateFactor);
                    resultIndex++;
                    duplicateFactorIterations = 0;
                    pixelSum = 0;
                }

                for (int r = 0; r < duplicateFactor; r++) {
                    int exactPixelPos = pixelsLastIndex + (r * rowLength) + currentIndex;
                    
                    if (frameType == AV_PIX_FMT_BGR24) {
                        pixelSum += bytesUtils.pixelToBit(pixelsCache[exactPixelPos + 2], pixelsCache[exactPixelPos + 1], pixelsCache[exactPixelPos]);
                    } else {
                        pixelSum += bytesUtils.pixelToBit(pixelsCache[exactPixelPos], pixelsCache[exactPixelPos + 1], pixelsCache[exactPixelPos + 2]);
                    }
                }

                currentIndex += RGB_CHANNELS;
                duplicateFactorIterations++;
            }

            if (duplicateFactorIterations >= duplicateFactor) {
                bitsRowCache[resultIndex] = bytesUtils.pixelToBit(pixelSum, duplicateFactor);
            }

            pixelsLastIndex += rowLength * duplicateFactor;

            // --- StringBuilder / Integer.parseInt を完全排除した高速ストリーム書き出し ---
            for (int b = 0; b < requiredBitsRowLength; b++) {
                int bit = bitsRowCache[b];
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

    // オリジナルの zeroBytesCount のバッファリングロジックを確実にトレースするコアロジック
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

    // 連続する0バイトを、L2キャッシュを汚さずに一気に出力する最速のバルクフラッシュ
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
