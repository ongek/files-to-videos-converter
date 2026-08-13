package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static io.github.eoinkanro.filestovideosconverter.utils.BytesUtils.ZERO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    private static final int READ_CHUNK_SIZE = 65536; // 64KB ディスクバッファ

    // YUV420P における輝度値（フルレンジ）
    private static final byte Y_BLACK = (byte) 0x00; // 黒 (Y = 0)
    private static final byte Y_WHITE = (byte) 0xFF; // 白 (Y = 255)

    private static final class EncoderContext {
        int currentPixelIndex = 0;
    }

    public FilesToVideosTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);

        final int localLastZeroBytesCount = fileUtils.calculateLastZeroBytesAmount(processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        final int imgWidth = inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH);
        final int imgHeight = inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT);
        final int duplicateFactor = inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR);
        final int maxPixelsCapacity = imgWidth * imgHeight;

        final int localTempRowLength = imgWidth / duplicateFactor;
        final byte[] localTempRow = new byte[localTempRowLength];

        final int localRowCacheLength = imgWidth;
        final byte[] localRowCache = new byte[localRowCacheLength];

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                // --- 【ダイレクト YUV420P メモリ構築】 ---
                // Yプレーン (輝度/白黒): 全ピクセル分 (1 Byte/px)
                final long ySize = maxPixelsCapacity;
                // U, V プレーン (色差): 1/4 ピクセル分
                final long uvSize = maxPixelsCapacity / 4;

                final MemorySegment ySegment = arena.allocate(ySize, 64); // M4 Cache line アライメント
                final MemorySegment uSegment = arena.allocate(uvSize, 64);
                final MemorySegment vSegment = arena.allocate(uvSize, 64);

                // U/Vプレーンはモノクロのため固定値 128 (0x80: 中立グレー) を初期化時に1回埋めるだけ
                uSegment.fill((byte) 0x80);
                vSegment.fill((byte) 0x80);

                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec);
                videoRecorder.setPixelFormat(AV_PIX_FMT_YUV420P);

                // チューニングパラメタ
                videoRecorder.setVideoQuality(90);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setMaxBFrames(0);
                videoRecorder.setOption("bf", "0");

                videoRecorder.start();

                // 3プレーン (Y, U, V) のバッファ構造を作成
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 1);
                reusableFrame.image = new ByteBuffer[]{
                        ySegment.asByteBuffer(),
                        uSegment.asByteBuffer(),
                        vSegment.asByteBuffer()
                };
                reusableFrame.imageStride = new int[]{imgWidth, imgWidth / 2, imgWidth / 2};

                final EncoderContext ctx = new EncoderContext();
                int localTempRowIndex = 0;

                final byte[] readBuffer = new byte[READ_CHUNK_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int aByte = readBuffer[i] & 0xFF;

                        // 1ピクセルあたり1バイトの Y値 (0x00 / 0xFF) を直接出力
                        localTempRow[localTempRowIndex++] = ((aByte & 0x80) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x40) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x20) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x10) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x08) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x04) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x02) != 0) ? Y_WHITE : Y_BLACK;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x01) != 0) ? Y_WHITE : Y_BLACK;

                        if (localTempRowIndex >= localTempRowLength) {
                            flushRowToNativeYSegment(localTempRow, localTempRowLength, localRowCache, localRowCacheLength,
                                    duplicateFactor, maxPixelsCapacity, ySegment, videoRecorder, reusableFrame, ctx);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾パディング
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    flushRowToNativeYSegment(localTempRow, localTempRowLength, localRowCache, localRowCacheLength,
                            duplicateFactor, maxPixelsCapacity, ySegment, videoRecorder, reusableFrame, ctx);
                }

                // 残余バッファを一括パディングして出力
                if (ctx.currentPixelIndex > 0) {
                    if (ctx.currentPixelIndex < maxPixelsCapacity) {
                        final int remainingBytes = maxPixelsCapacity - ctx.currentPixelIndex;
                        ySegment.asSlice(ctx.currentPixelIndex, remainingBytes).fill(Y_WHITE);
                    }

                    videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                    taskStatistics.poll();
                }
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        if (resultVideoFile != null) {
            convertHev1ToHvc1(resultVideoFile);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    /**
     * Yプレーン（1 Byte/px）へ直接バルクコピー
     */
    private void flushRowToNativeYSegment(byte[] localTempRow, final int localTempRowLength,
                                           byte[] localRowCache, final int localRowCacheLength,
                                           final int duplicateFactor, final int maxPixelsCapacity,
                                           MemorySegment ySegment, FFmpegFrameRecorder videoRecorder,
                                           Frame reusableFrame, EncoderContext ctx) throws Exception {

        // --- FAST PATH: duplicateFactor == 1 ---
        if (duplicateFactor == 1) {
            if (ctx.currentPixelIndex + localTempRowLength > maxPixelsCapacity) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }

            // Java 26 FFM API: byte配列の一括メモリ転送
            MemorySegment.copy(localTempRow, 0, ySegment, ValueLayout.JAVA_BYTE, ctx.currentPixelIndex, localTempRowLength);
            ctx.currentPixelIndex += localTempRowLength;
            return;
        }

        // --- SLOW PATH: duplicateFactor > 1 ---
        int cacheIdx = 0;
        for (int i = 0; i < localTempRowLength; i++) {
            final byte px = localTempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            if (ctx.currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }

            MemorySegment.copy(localRowCache, 0, ySegment, ValueLayout.JAVA_BYTE, ctx.currentPixelIndex, localRowCacheLength);
            ctx.currentPixelIndex += localRowCacheLength;
        }
    }

    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            final int searchSize = (int) Math.min(raf.length(), 64 * 1024);
            final byte[] searchBuffer = new byte[searchSize];
            raf.readFully(searchBuffer);

            for (int i = 0; i <= searchSize - 4; i++) {
                if (searchBuffer[i] == 0x68 && searchBuffer[i+1] == 0x65 &&
                    searchBuffer[i+2] == 0x76 && searchBuffer[i+3] == 0x31) {
                    raf.seek(i);
                    raf.write(new byte[]{0x68, 0x76, 0x63, 0x31});
                    return;
                }
            }
            log.warn("FourCC 'hev1' not patched. Compatibility may be affected.");
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC.", e);
        }
    }
}
