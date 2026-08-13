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
    private byte[] pixelsCache = new byte[0];

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

            // 残った端数ビットの処理（フレーム・画像終端の整合性維持）
            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                appendByteToStream(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }

            // 元ファイルに存在していた末尾ゼロ（-zXXで記録された分）のみ復元
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

            processImageOptimized(requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }
    }

    /**
     * 超高速化された画像判定・ビット復元ロジック
     */
    private void processImageOptimized(int pixelsLength, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;
        final int rowStride = imageWidth * RGB_CHANNELS;
        final int pixelsIterations = pixelsLength / rowStride / df;
        final int bitsPerRow = imageWidth / df;

        // 1. カラーフォーマットに応じたチャンネルオフセットの事前計算 (If文排除)
        final int rOffset = (frameType == AV_PIX_FMT_BGR24) ? 2 : 0;
        final int gOffset = 1;
        final int bOffset = (frameType == AV_PIX_FMT_BGR24) ? 0 : 2;

        final int colByteStride = df * RGB_CHANNELS;
        final int blockRowStride = df * rowStride;

        // 2. 8ビット一括処理用の指標計算
        final int fullBytesPerRow = bitsPerRow >> 3; // bitsPerRow / 8
        final int remainingBits = bitsPerRow & 7;    // bitsPerRow % 8

        int bitCount = currentBitsCount;
        int byteVal = currentByteVal;

        for (int i = 0; i < pixelsIterations; i++) {
            int blockStartByte = i * blockRowStride;
            int bitIdx = 0;

            // --- Fast Path: 8ビット（1バイト）を直接組み立てて出力（bitCount == 0 の時） ---
            if (bitCount == 0 && fullBytesPerRow > 0) {
                for (int byteIdx = 0; byteIdx < fullBytesPerRow; byteIdx++) {
                    int constructedByte = 0;

                    for (int b = 0; b < 8; b++) {
                        int colByte = bitIdx * colByteStride;
                        int pixelSum = 0;

                        for (int r = 0; r < df; r++) {
                            int rowAddr = blockStartByte + (r * rowStride) + colByte;

                            for (int c = 0; c < df; c++) {
                                int addr = rowAddr + (c * RGB_CHANNELS);
                                pixelSum += bytesUtils.pixelToBit(
                                        pixelsCache[addr + rOffset],
                                        pixelsCache[addr + gOffset],
                                        pixelsCache[addr + bOffset]
                                );
                            }
                        }

                        int bit = bytesUtils.pixelToBit(pixelSum, df);
                        constructedByte = (constructedByte << 1) | (bit & 1);
                        bitIdx++;
                    }

                    // 1バイト完成。カウントチェック無しで即時出力ストリームへ保留・書き出し
                    appendByteToStream(constructedByte, outputStream);
                }
            }

            // --- Slow Path / 残余ビット処理（端数ビットが存在する場合や、行末の余り） ---
            for (; bitIdx < bitsPerRow; bitIdx++) {
                int colByte = bitIdx * colByteStride;
                int pixelSum = 0;

                for (int r = 0; r < df; r++) {
                    int rowAddr = blockStartByte + (r * rowStride) + colByte;

                    for (int c = 0; c < df; c++) {
                        int addr = rowAddr + (c * RGB_CHANNELS);
                        pixelSum += bytesUtils.pixelToBit(
                                pixelsCache[addr + rOffset],
                                pixelsCache[addr + gOffset],
                                pixelsCache[addr + bOffset]
                        );
                    }
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

        // 状態の書き戻し
        this.currentBitsCount = bitCount;
        this.currentByteVal = byteVal;
    }

    /**
     * ゼロバイト遅延書き込み処理
     */
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
