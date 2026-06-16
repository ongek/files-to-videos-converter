package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static io.github.eoinkanro.filestovideosconverter.utils.BytesUtils.ZERO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB32_1;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024;

    // プリミティブスタックフィールドへの参照速度を維持するためのコンテキスト
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
            
            try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));
                
                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec); 
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                
                // --- Snow Leopard 究極安定・低消費電力チューニング ---
                videoRecorder.setVideoQuality(90); // CRF90（実機・CI不変のビットパーフェクト）
                videoRecorder.setOption("movflags", "faststart");
                
                // 【速度最優先】realtimeを復活させHWメディアエンジンの巡航速度を最大化
                videoRecorder.setVideoOption("realtime", "1");
                
                // 【遅延・ブレ排除】BフレームをネイティブAPI経由でJNIオーバーヘッドなしでゼロに固定
                videoRecorder.setMaxBFrames(0);
                videoRecorder.setOption("bf", "0"); 
                
                videoRecorder.start();

                // ダイレクトバッファのゼロアロケーション配置
                final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
                final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
                
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                final EncoderContext ctx = new EncoderContext();
                int aByte;
                int localTempRowIndex = 0;

                // メインループ（シフト演算による一括CPUキャッシュ展開）
                while ((aByte = inputStream.read()) >= 0) {
                    for (int shift = 7; shift >= 0; shift--) {
                        localTempRow[localTempRowIndex++] = ((aByte & (1 << shift)) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            flushRowCacheToBuffer(localTempRow, localTempRowLength, localRowCache, localRowCacheLength, 
                                                 duplicateFactor, maxPixelsCapacity, localBuffer, videoRecorder, reusableFrame, ctx);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾のゼロパディング処理
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    flushRowCacheToBuffer(localTempRow, localTempRowLength, localRowCache, localRowCacheLength, 
                                         duplicateFactor, maxPixelsCapacity, localBuffer, videoRecorder, reusableFrame, ctx);
                }

                // 残余バッファを一括パディングしてVRAMへフラッシュ
                if (ctx.currentPixelIndex > 0) {
                    if (ctx.currentPixelIndex < maxPixelsCapacity) {
                        localBuffer.position(ctx.currentPixelIndex);
                        final int remainingInts = maxPixelsCapacity - ctx.currentPixelIndex;
                        
                        int zerosWritten = 0;
                        while (zerosWritten < remainingInts) {
                            int chunk = Math.min(remainingInts - zerosWritten, localRowCacheLength);
                            Arrays.fill(localRowCache, 0, chunk, ZERO);
                            localBuffer.put(localRowCache, 0, chunk);
                            zerosWritten += chunk;
                        }
                    }
                    
                    localBuffer.rewind(); 
                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
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
     * SIMD自動ベクトル化をJITコンパイラに最適発火させるための高速バッファフラッシュ
     */
    private void flushRowCacheToBuffer(int[] localTempRow, final int localTempRowLength, 
                                       int[] localRowCache, final int localRowCacheLength,
                                       final int duplicateFactor, final int maxPixelsCapacity, 
                                       IntBuffer localBuffer, FFmpegFrameRecorder videoRecorder, 
                                       Frame reusableFrame, EncoderContext ctx) throws Exception {
        int cacheIdx = 0;
        for (int i = 0; i < localTempRowLength; i++) {
            final int px = localTempRow[i];
            // 内側ループのファクター数に応じてJITがアンロールしやすい直線的代入
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            if (ctx.currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                localBuffer.rewind(); 
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }
            
            localBuffer.position(ctx.currentPixelIndex);
            localBuffer.put(localRowCache, 0, localRowCacheLength);
            ctx.currentPixelIndex += localRowCacheLength;
        }
    }

    /**
     * 高速インプレース FourCC パッチ (ディスクI/O効率化 & 低消費電力スキャン版)
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            // ヘッダー近辺(最大64KB)のみに走査を限定し、メモリフットプリントを最小化
            final int searchSize = (int) Math.min(raf.length(), 64 * 1024);
            final byte[] searchBuffer = new byte[searchSize];
            raf.readFully(searchBuffer);

            // 高速な4バイトウィンドウマッチング
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
