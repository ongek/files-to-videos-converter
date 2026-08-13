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
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    private static final int READ_CHUNK_SIZE = 65536; // 64KB ディスク一括バッファ

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
        final int[] localTempRow = new int[localTempRowLength];

        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        final int localRowCacheLength = imgWidth;
        final int[] localRowCache = new int[localRowCacheLength];

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            // 【Java 26 FFM API】Arena でネイティブメモリのライフサイクルを完全自動管理 (GCゼロ)
            try (Arena arena = Arena.ofConfined();
                 BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                // 【M4 アーキテクチャ最適化】64バイトアライメント（M4のL1/L2キャッシュラインサイズ）でネイティブ領域確保
                final MemorySegment nativePixelSegment = arena.allocate((long) maxPixelsCapacity * 4, 64);
                final ByteBuffer reusableByteBuffer = nativePixelSegment.asByteBuffer();

                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec);
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);

                // --- Snow Leopard 究極安定・低消費電力チューニング ---
                videoRecorder.setVideoQuality(90);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setMaxBFrames(0);
                videoRecorder.setOption("bf", "0");

                videoRecorder.start();

                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                final EncoderContext ctx = new EncoderContext();
                int localTempRowIndex = 0;

                final byte[] readBuffer = new byte[READ_CHUNK_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int aByte = readBuffer[i] & 0xFF;

                        // M4のパイプライン処理に最適化された直列化アンロール
                        localTempRow[localTempRowIndex++] = ((aByte & 0x80) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x40) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x20) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x10) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x08) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x04) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x02) != 0) ? pixelOne : pixelZero;
                        localTempRow[localTempRowIndex++] = ((aByte & 0x01) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            flushRowToNativeMemorySegment(localTempRow, localTempRowLength, localRowCache, localRowCacheLength,
                                    duplicateFactor, maxPixelsCapacity, nativePixelSegment, videoRecorder, reusableFrame, ctx);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾のゼロパディング処理
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    flushRowToNativeMemorySegment(localTempRow, localTempRowLength, localRowCache, localRowCacheLength,
                            duplicateFactor, maxPixelsCapacity, nativePixelSegment, videoRecorder, reusableFrame, ctx);
                }

                // 残余バッファを一括パディングしてVRAM/エンコーダへフラッシュ
                if (ctx.currentPixelIndex > 0) {
                    if (ctx.currentPixelIndex < maxPixelsCapacity) {
                        final int remainingInts = maxPixelsCapacity - ctx.currentPixelIndex;

                        // MemorySegment.fill によるハードウェア一括メモリ埋め (0xFF)
                        long offsetBytes = (long) ctx.currentPixelIndex * 4;
                        long lengthBytes = (long) remainingInts * 4;
                        nativePixelSegment.asSlice(offsetBytes, lengthBytes).fill((byte) 0xFF);
                    }

                    videoRecorder.record(reusableFrame, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA);
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
     * 【Java 26 FFM API】MemorySegment への直接バルクコピー
     * (NIO IntBuffer 境界チェックと JNI オーバーヘッドを完全排除)
     */
    private void flushRowToNativeMemorySegment(int[] localTempRow, final int localTempRowLength,
                                                int[] localRowCache, final int localRowCacheLength,
                                                final int duplicateFactor, final int maxPixelsCapacity,
                                                MemorySegment nativePixelSegment, FFmpegFrameRecorder videoRecorder,
                                                Frame reusableFrame, EncoderContext ctx) throws Exception {

        // --- FAST PATH: duplicateFactor == 1 ---
        if (duplicateFactor == 1) {
            if (ctx.currentPixelIndex + localTempRowLength > maxPixelsCapacity) {
                videoRecorder.record(reusableFrame, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }

            // Java 26 FFM API: JITがARM64のSIMD一括ストア命令(stp/memcpy)に直接変換
            MemorySegment.copy(localTempRow, 0, nativePixelSegment, ValueLayout.JAVA_INT, (long) ctx.currentPixelIndex * 4, localTempRowLength);
            ctx.currentPixelIndex += localTempRowLength;
            return;
        }

        // --- SLOW PATH: duplicateFactor > 1 ---
        int cacheIdx = 0;
        for (int i = 0; i < localTempRowLength; i++) {
            final int px = localTempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            if (ctx.currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                videoRecorder.record(reusableFrame, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }

            MemorySegment.copy(localRowCache, 0, nativePixelSegment, ValueLayout.JAVA_INT, (long) ctx.currentPixelIndex * 4, localRowCacheLength);
            ctx.currentPixelIndex += localRowCacheLength;
        }
    }

    /**
     * 高速インプレース FourCC パッチ
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            final int searchSize = (int) Math.min(raf.length(), 64 * 1024);
            final byte[] searchBuffer = new byte[searchSize];
            raf.readFully(searchBuffer);

            for (int i = 0; i <= searchSize - 4; i++) {
                if (searchBuffer[i] == 0x68 && searchBuffer[i+1] == 0x65 &&
                    searchBuffer[i+2] == 0x76 && searchBuffer[i+3] == 0x31) { // 'h' 'e' 'v' '1'
                    raf.seek(i);
                    raf.write(new byte[]{0x68, 0x76, 0x63, 0x31}); // 'h' 'v' 'c' '1'
                    return;
                }
            }
            log.warn("FourCC 'hev1' not patched. Compatibility may be affected.");
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC.", e);
        }
    }
}
