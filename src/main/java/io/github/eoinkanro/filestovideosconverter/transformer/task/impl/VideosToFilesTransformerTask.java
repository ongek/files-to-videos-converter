package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int RGB_CHANNELS = 3;
    private static final int QUEUE_CAPACITY = 16; // パイプライン用キュー容量

    private final byte[] bulkZeroBuffer = new byte[16384];

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

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), 65536)) {
            int duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());

            // パイプライン処理を実行
            processPipeline(processData, duplicateFactor, outputStream);

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

    private void processPipeline(File video, int duplicateFactor, OutputStream outputStream) throws IOException {
        BlockingQueue<FramePacket> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();

        // ----------------------------------------------------
        // 【Consumer】スレッドB: ピクセル解析 & ファイル書き込み
        // ----------------------------------------------------
        Future<Void> consumerFuture = consumerExecutor.submit(() -> {
            int currentByteVal = 0;
            int currentBitsCount = 0;
            long zeroBytesCount = 0;

            long duplicateFactorPixels = (long) duplicateFactor * duplicateFactor;
            long oneMinThreshold = duplicateFactorPixels * -8388609L;

            while (true) {
                FramePacket packet = queue.take();
                if (packet.isPoisonPill) break;

                ByteBuffer buffer = packet.buffer;
                int imageWidth = packet.imageWidth;
                int imageHeight = packet.imageHeight;
                boolean isBGR = (packet.pixelFormat == AV_PIX_FMT_BGR24);

                int rowStride = imageWidth * RGB_CHANNELS;
                int pixelsIterations = imageHeight / duplicateFactor;
                int bitsPerRow = imageWidth / duplicateFactor;

                for (int i = 0; i < pixelsIterations; i++) {
                    int blockStartOffset = i * duplicateFactor * rowStride;

                    for (int b = 0; b < bitsPerRow; b++) {
                        long pixelSum = 0;
                        int colOffset = b * duplicateFactor * RGB_CHANNELS;

                        for (int r = 0; r < duplicateFactor; r++) {
                            int rowOffset = blockStartOffset + (r * rowStride) + colOffset;

                            for (int c = 0; c < duplicateFactor; c++) {
                                int pOffset = rowOffset + (c * RGB_CHANNELS);

                                int rVal, gVal, bVal;
                                // ByteBuffer.get(index) によるダイレクト参照 (JITによりネイティブ命令へ最適化)
                                if (isBGR) {
                                    bVal = buffer.get(pOffset) & 0xFF;
                                    gVal = buffer.get(pOffset + 1) & 0xFF;
                                    rVal = buffer.get(pOffset + 2) & 0xFF;
                                } else {
                                    rVal = buffer.get(pOffset) & 0xFF;
                                    gVal = buffer.get(pOffset + 1) & 0xFF;
                                    bVal = buffer.get(pOffset + 2) & 0xFF;
                                }

                                int argb = 0xFF000000 | (rVal << 16) | (gVal << 8) | bVal;
                                pixelSum += argb;
                            }
                        }

                        // ビット判定
                        int bit = pixelSum > oneMinThreshold ? 0 : 1;

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
                taskStatistics.poll();
            }

            // 残りビットのフラッシュ処理
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
            return null;
        });

        // ----------------------------------------------------
        // 【Producer】スレッドA: M4 Media Engine によるHWデコード
        // ----------------------------------------------------
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("hwaccel", "videotoolbox");
            grabber.setOption("threads", "auto");
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (frame.image == null || frame.image.length == 0) continue;

                ByteBuffer bb = (ByteBuffer) frame.image[0];

                FramePacket packet = new FramePacket(
                        bb,
                        frame.imageWidth,
                        frame.imageHeight,
                        grabber.getPixelFormat(),
                        false
                );

                queue.put(packet);
            }

            queue.put(FramePacket.POISON_PILL);
            consumerFuture.get();

        } catch (Exception e) {
            throw new IOException("Error during pipeline frame processing", e);
        } finally {
            consumerExecutor.shutdown();
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

    private static class FramePacket {
        static final FramePacket POISON_PILL = new FramePacket(null, 0, 0, 0, true);

        final ByteBuffer buffer;
        final int imageWidth;
        final int imageHeight;
        final int pixelFormat;
        final boolean isPoisonPill;

        FramePacket(ByteBuffer buffer, int imageWidth, int imageHeight, int pixelFormat, boolean isPoisonPill) {
            this.buffer = buffer;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.pixelFormat = pixelFormat;
            this.isPoisonPill = isPoisonPill;
        }
    }
}
