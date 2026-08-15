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
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    private static final int READ_CHUNK_SIZE = 65536; // 64KB ディスク一括バッファ

    // YUV420p 用の輝度(Y)値: 白 = 255 (0xFF), 黒 = 0 (0x00)
    private static final byte Y_ONE = (byte) 0x00; // 黒
    private static final byte Y_ZERO = (byte) 0xFF; // 白

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

        final int yPlaneSize = imgWidth * imgHeight;
        final int uvPlaneSize = (imgWidth / 2) * (imgHeight / 2);
        final int frameSizeYuv = yPlaneSize + (uvPlaneSize * 2); // YUV420P トータルバイト数

        final int localTempRowLength = imgWidth / duplicateFactor;
        final byte[] localTempRow = new byte[localTempRowLength];
        final byte[] localRowCache = new byte[imgWidth];

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                // YUV420Pフレーム全体のネイティブメモリを確保 (64バイトアライメント)
                final MemorySegment nativeFrameSegment = arena.allocate((long) frameSizeYuv, 64);
                
                // 色度(U/V)プレーンを固定値 128 (0x80 = 完全無色/モノクロ) で事前に初期化
                final long uOffset = yPlaneSize;
                final long vOffset = yPlaneSize + uvPlaneSize;
                nativeFrameSegment.asSlice(uOffset, (long) uvPlaneSize * 2).fill((byte) 0x80);

                // 全体バッファを取得
                final ByteBuffer yuvBuffer = nativeFrameSegment.asByteBuffer();

                // JavaCV Frame 設定（配列ではなく Buffer 直接参照に修正）
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 1);
                reusableFrame.image = yuvBuffer; // ここを修正 (JavaCV Frame仕様)
                reusableFrame.imageStride = imgWidth;

                // FFmpeg レコーダー設定
                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec);
                videoRecorder.setPixelFormat(AV_PIX_FMT_YUV420P);

                // FourCC パッチ処理を不要にする設定
                videoRecorder.setOption("tag:v", "hvc1");

                videoRecorder.setVideoQuality(90);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setMaxBFrames(0);
                videoRecorder.setOption("bf", "0");

                videoRecorder.start();

                int currentPixelIndex = 0;
                int localTempRowIndex = 0;

                final byte[] readBuffer = new byte[READ_CHUNK_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int aByte = readBuffer[i] & 0xFF;

                        // byte配列（Y輝度値）に直接展開
                        localTempRow[localTempRowIndex++] = ((aByte & 0x80) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x40) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x20) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x10) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x08) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x04) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x02) != 0) ? Y_ONE : Y_ZERO;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x01) != 0) ? Y_ONE : Y_ZERO;

                        if (localTempRowIndex >= localTempRowLength) {
                            currentPixelIndex = flushRowToNativeMemory(
                                    localTempRow, localTempRowLength, localRowCache,
                                    duplicateFactor, yPlaneSize, nativeFrameSegment,
                                    videoRecorder, reusableFrame, currentPixelIndex
                            );
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾パディング処理
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, Y_ZERO);
                    currentPixelIndex = flushRowToNativeMemory(
                            localTempRow, localTempRowLength, localRowCache,
                            duplicateFactor, yPlaneSize, nativeFrameSegment,
                            videoRecorder, reusableFrame, currentPixelIndex
                    );
                }

                // 残余バッファを一括パディングして最終フレーム書き込み
                if (currentPixelIndex > 0) {
                    if (currentPixelIndex < yPlaneSize) {
                        long offsetBytes = currentPixelIndex;
                        long lengthBytes = (long) yPlaneSize - currentPixelIndex;
                        nativeFrameSegment.asSlice(offsetBytes, lengthBytes).fill(Y_ZERO);
                    }
                    videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                    taskStatistics.poll();
                }
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private int flushRowToNativeMemory(byte[] localTempRow, int localTempRowLength,
                                        byte[] localRowCache, int duplicateFactor,
                                        int yPlaneSize, MemorySegment nativeFrameSegment,
                                        FFmpegFrameRecorder videoRecorder, Frame reusableFrame,
                                        int currentPixelIndex) throws Exception {

        if (duplicateFactor == 1) {
            if (currentPixelIndex + localTempRowLength > yPlaneSize) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                taskStatistics.poll();
                currentPixelIndex = 0;
            }

            // SIMD最適化バルクメモリコピー (JAVA_BYTE)
            MemorySegment.copy(localTempRow, 0, nativeFrameSegment, ValueLayout.JAVA_BYTE, currentPixelIndex, localTempRowLength);
            return currentPixelIndex + localTempRowLength;
        }

        // duplicateFactor > 1 の拡大処理
        int cacheIdx = 0;
        for (int i = 0; i < localTempRowLength; i++) {
            final byte px = localTempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            if (currentPixelIndex + localRowCache.length > yPlaneSize) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                taskStatistics.poll();
                currentPixelIndex = 0;
            }

            MemorySegment.copy(localRowCache, 0, nativeFrameSegment, ValueLayout.JAVA_BYTE, currentPixelIndex, localRowCache.length);
            currentPixelIndex += localRowCache.length;
        }

        return currentPixelIndex;
    }
}
