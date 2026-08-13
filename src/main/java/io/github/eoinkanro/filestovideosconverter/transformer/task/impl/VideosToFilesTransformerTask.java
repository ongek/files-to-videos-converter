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

        // 64KBバッファで I/O コストを削減
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), 65536)) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());

            // 状態初期化
            currentBitsCount = 0;
            currentByteVal = 0;
            zeroBytesCount = 0;

            processFile(processData, outputStream);

            // 残った端数ビットの処理
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

            processImageLimit(requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }
    }

    /**
     * duplicateFactorの分岐を備えた最高速度処理ロジック
     */
    private void processImageLimit(int pixelsLength, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;

        // カラーフォーマットに応じたチャンネルオフセット
        final int rOffset = (frameType == AV_PIX_FMT_BGR24) ? 2 : 0;
        final int gOffset = 1;
        final int bOffset = (frameType == AV_PIX_FMT_BGR24) ? 0 : 2;

        int bitCount = this.currentBitsCount;
        int byteVal = this.currentByteVal;

        // =========================================================================
        // FAST PATH: duplicateFactor == 1 (ブロック計算を全スキップし、1次元リニアスキャン)
        // =========================================================================
        if (df == 1) {
            final byte[] cache = pixelsCache;
            for (int addr = 0; addr < pixelsLength; addr += RGB_CHANNELS) {
                int pBit = bytesUtils.pixelToBit(
                        cache[addr + rOffset],
                        cache[addr + gOffset],
                        cache[addr + bOffset]
                );

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
        // SLOW PATH: duplicateFactor > 1 (加算のみのポインタ的走査)
        // =========================================================================
        final int rowStride = imageWidth * RGB_CHANNELS;
        final int pixelsIterations = pixelsLength / rowStride / df;
        final int bitsPerRow = imageWidth / df;

        final int colByteStride = df * RGB_CHANNELS;
        final int blockRowStride = df * rowStride;
        final byte[] cache = pixelsCache;

        for (int i = 0; i < pixelsIterations; i++) {
            final int blockStartByte = i * blockRowStride;

            for (int b = 0; b < bitsPerRow; b++) {
                final int colByte = b * colByteStride;
                int pixelSum = 0;

                int rowAddr = blockStartByte + colByte;
                for (int r = 0; r < df; r++) {
                    int addr = rowAddr;
                    for (int c = 0; c < df; c++) {
                        pixelSum += bytesUtils.pixelToBit(
                                cache[addr + rOffset],
                                cache[addr + gOffset],
                                cache[addr + bOffset]
                        );
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
