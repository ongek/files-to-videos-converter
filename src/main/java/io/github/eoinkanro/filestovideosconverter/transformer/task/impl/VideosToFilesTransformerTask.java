package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int RGB_CHANNELS = 3;
    private static final Unsafe UNSAFE;
    private static final int QUEUE_CAPACITY = 16; // フレーム用パイプラインバッファ

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Unsafe", e);
        }
    }

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

            // パイプライン処理の実行
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

            // BytesUtils.ONE_MIN * duplicateFactor^2 の事前計算
            long duplicateFactorPixels = (long) duplicateFactor * duplicateFactor;
            long oneMinThreshold = duplicateFactorPixels * -8388609L;

            while (true) {
                FramePacket packet = queue.take();
                if (packet.isPoisonPill) break;

                long nativeAddr = packet.nativeAddress;
                int imageWidth = packet.imageWidth;
                int imageHeight = packet.imageHeight;
                boolean isBGR = (packet.pixelFormat == AV_PIX_FMT_BGR24);

                int rowStride = imageWidth * RGB_CHANNELS;
                int pixelsIterations = imageHeight / duplicateFactor;
                int bitsPerRow = imageWidth / duplicateFactor;

                for (int i = 0; i < pixelsIterations; i++) {
                    long blockStartAddr = nativeAddr + ((long) i * duplicateFactor * rowStride);

                    for (int b = 0; b < bitsPerRow; b++) {
                        long pixelSum = 0;
                        long colOffset = (long) b * duplicateFactor * RGB_CHANNELS;

                        // Unsafe を用いた Native メモリダイレクト判定
                        for (int r = 0; r < duplicateFactor; r++) {
                            long rowAddr = blockStartAddr + ((long) r * rowStride) + colOffset;

                            for (int c = 0; c < duplicateFactor; c++) {
                                long pAddr = rowAddr + ((long) c * RGB_CHANNELS);

                                int rVal, gVal, bVal;
                                if (isBGR) {
                                    bVal = UNSAFE.getByte(pAddr) & 0xFF;
                                    gVal = UNSAFE.getByte(pAddr + 1) & 0xFF;
                                    rVal = UNSAFE.getByte(pAddr + 2) & 0xFF;
                                } else {
                                    rVal = UNSAFE.getByte(pAddr) & 0xFF;
                                    gVal = UNSAFE.getByte(pAddr + 1) & 0xFF;
                                    bVal = UNSAFE.getByte(pAddr + 2) & 0xFF;
                                }

                                // BytesUtils.pixelToBit のインライン高速化 (0xFF000000 | (R<<16) | (G<<8) | B)
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

            // フラッシュ処理
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
            // Apple Silicon ハードウェアデコーダーを有効化
            grabber.setOption("hwaccel", "videotoolbox");
            grabber.setOption("threads", "auto");
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (frame.image == null || frame.image.length == 0) continue;

                ByteBuffer bb = (ByteBuffer) frame.image[0];
                // Direct Buffer の Native アドレスを抽出 (Zero-Copy)
                long nativeAddress = ((sun.nio.ch.DirectBuffer) bb).address();

                FramePacket packet = new FramePacket(
                        nativeAddress,
                        frame.imageWidth,
                        frame.imageHeight,
                        grabber.getPixelFormat(),
                        false
                );

                queue.put(packet); // キューが満杯ならスレッドAが自然待機（バックプレッシャー）
            }

            queue.put(FramePacket.POISON_PILL); // 終了シグナル
            consumerFuture.get(); // 解析側スレッドの例外・終了を検知

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

    // パイプライン伝送用軽量データ転送オブジェクト
    private static class FramePacket {
        static final FramePacket POISON_PILL = new FramePacket(0, 0, 0, 0, true);

        final long nativeAddress;
        final int imageWidth;
        final int imageHeight;
        final int pixelFormat;
        final boolean isPoisonPill;

        FramePacket(long nativeAddress, int imageWidth, int imageHeight, int pixelFormat, boolean isPoisonPill) {
            this.nativeAddress = nativeAddress;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.pixelFormat = pixelFormat;
            this.isPoisonPill = isPoisonPill;
        }
    }
}
