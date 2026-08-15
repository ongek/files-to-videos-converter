package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.nio.ByteBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 65536; // 64KB I/O バッファ
    private final byte[] bulkZeroBuffer = new byte[16384];
    private byte[] yPlaneCache = new byte[0];

    private int duplicateFactor;
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

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), IO_BUFFER_SIZE)) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());

            currentBitsCount = 0;
            currentByteVal = 0;
            zeroBytesCount = 0;

            processFile(processData, outputStream);

            // 端数ビットのフラッシュ
            if (currentBitsCount > 0) {
                currentByteVal <<= (8 - currentBitsCount);
                appendByteToStream(currentByteVal, outputStream);
                currentByteVal = 0;
                currentBitsCount = 0;
            }

            // 末尾ゼロの復元
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
            // YUV420P等、デコード後の生のフレームをそのまま取得
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (frame.image == null || frame.image[0] == null) {
                    continue;
                }

                int imgWidth = frame.imageWidth;
                int imgHeight = frame.imageHeight;
                int yPlaneSize = imgWidth * imgHeight;

                // キャッシュバッファ確保
                if (yPlaneCache.length < yPlaneSize) {
                    yPlaneCache = new byte[yPlaneSize];
                }

                // Yプレーン（輝度）のみをダイレクト抽出
                ByteBuffer yBuffer = (ByteBuffer) frame.image[0];
                yBuffer.get(yPlaneCache, 0, yPlaneSize);

                // 解読ルーチン実行
                processYPlane(yPlaneCache, imgWidth, imgHeight, outputStream);

                taskStatistics.poll();
            }
        }
    }

    /**
     * Yプレーン（輝度）ダイレクト読み取りによる高速デコード
     */
    private void processYPlane(byte[] yPlane, int width, int height, OutputStream outputStream) throws IOException {
        final int df = duplicateFactor;
        int bitCount = this.currentBitsCount;
        int byteVal = this.currentByteVal;

        // --- FAST PATH: duplicateFactor == 1 ---
        if (df == 1) {
            final int totalPixels = width * height;
            for (int i = 0; i < totalPixels; i++) {
                // 輝度(Y)が 128 未満なら 1(黒)、128 以上なら 0(白)
                int bit = ((yPlane[i] & 0xFF) < 128) ? 1 : 0;

                byteVal = (byteVal << 1) | bit;
                if (++bitCount == 8) {
                    appendByteToStream(byteVal, outputStream);
                    byteVal = 0;
                    bitCount = 0;
                }
            }
        } 
        // --- SLOW PATH: duplicateFactor > 1 ---
        else {
            final int blockRowStride = df * width;
            final int bitsPerRow = width / df;
            final int rows = height / df;
            final int threshold = (df * df * 255) / 2; // 判定しきい値

            for (int r = 0; r < rows; r++) {
                final int blockStart = r * blockRowStride;

                for (int b = 0; b < bitsPerRow; b++) {
                    final int colStart = b * df;
                    int ySum = 0;

                    // ブロック内の輝度合計を計算
                    for (int dr = 0; dr < df; dr++) {
                        int rowOffset = blockStart + (dr * width) + colStart;
                        for (int dc = 0; dc < df; dc++) {
                            ySum += (yPlane[rowOffset + dc] & 0xFF);
                        }
                    }

                    // 合計輝度がしきい値より小さければ黒 (1)
                    int bit = (ySum < threshold) ? 1 : 0;

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
