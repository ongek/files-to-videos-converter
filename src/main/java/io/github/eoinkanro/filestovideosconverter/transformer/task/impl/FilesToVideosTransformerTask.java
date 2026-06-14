package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB32_1;

public class FilesToVideosTransformerTask extends TransformerTask {

    // Lombok依存のエラーを根絶する、確実なロガー定義
    private static final Logger log = LogManager.getLogger(FilesToVideosTransformerTask.class);

    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    
    // BytesUtilsのColor.white.getRGB()と同じ値を直接定数化してコンパイルエラーを修正
    private static final int PIXEL_WHITE = 0xFFFFFFFF; 

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
        
        // BytesUtilsのインスタンスを毎回呼ばず、プリミティブ値としてキャッシュ
        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        final int[] localRowCache = new int[imgWidth];
        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);
            
            try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));
                
                // Appleシリコン（VideoToolbox）とGitHub Actions（Linux環境）での分岐を維持
                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec); 
                
                // VideoToolboxのネイティブ形式に合わせ、内部変換を高速化
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                videoRecorder.setVideoOption("f", "rawvideo");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setVideoBitrate(8000000);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.start();

                // ダイレクトバッファの確保（Java 26環境で最も効率的にネイティブメモリと連動）
                final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
                final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
                
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                int aByte;
                int localTempRowIndex = 0;
                // インジェクション用コンテキストクラス（プリミティブポインタ代わり）
                PixelWriterContext ctx = new PixelWriterContext(localBuffer, videoRecorder, reusableFrame, maxPixelsCapacity);

                while ((aByte = inputStream.read()) >= 0) {
                    // ビット展開の高速スキャン
                    for (int shift = 7; shift >= 0; shift--) {
                        localTempRow[localTempRowIndex++] = ((aByte & (1 << shift)) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            writeRowToBuffer(localTempRow, localTempRowLength, localRowCache, duplicateFactor, ctx);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾の不完全ビットブロックのフラッシュ（コピペを排除しメソッド化）
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, PIXEL_WHITE);
                    writeRowToBuffer(localTempRow, localTempRowLength, localRowCache, duplicateFactor, ctx);
                }

                // 最終フレームの残余領域のゼロパディング & 最終書き込み
                if (ctx.currentPixelIndex > 0) {
                    localBuffer.position(ctx.currentPixelIndex);
                    int remainingInts = maxPixelsCapacity - ctx.currentPixelIndex;
                    
                    int zerosWritten = 0;
                    while (zerosWritten < remainingInts) {
                        int chunk = Math.min(remainingInts - zerosWritten, imgWidth);
                        Arrays.fill(localRowCache, 0, chunk, PIXEL_WHITE);
                        localBuffer.put(localRowCache, 0, chunk);
                        zerosWritten += chunk;
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
     * 重複度（縦・横）を考慮して1行分のデータをIntBufferに一括転送する共通ロジック
     */
    private void writeRowToBuffer(int[] localTempRow, int tempRowLength, int[] localRowCache, 
                                  int duplicateFactor, PixelWriterContext ctx) throws Exception {
        // 1. 横展開の最適化
        int cacheIdx = 0;
        for (int i = 0; i < tempRowLength; i++) {
            final int px = localTempRow[i];
            // 配列への一括埋め込み（JITコンパイラによるループアンロールが最適に効く形状）
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        // 2. 縦展開とネイティブ転送
        for (int r = 0; r < duplicateFactor; r++) {
            if (ctx.currentPixelIndex + localRowCache.length > ctx.maxPixelsCapacity) {
                ctx.buffer.rewind(); 
                ctx.recorder.record(ctx.frame, AV_PIX_FMT_RGB32_1);
                taskStatistics.poll();
                ctx.currentPixelIndex = 0;
            }
            
            ctx.buffer.position(ctx.currentPixelIndex);
            ctx.buffer.put(localRowCache, 0, localRowCache.length);
            ctx.currentPixelIndex += localRowCache.length;
        }
    }

    /**
     * 高速FourCCパッチ (Appleエコシステム互換用)
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            long maxSearchBytes = Math.min(raf.length(), 64 * 1024);
            byte[] searchBuffer = new byte[(int) maxSearchBytes];
            raf.readFully(searchBuffer);

            for (int i = 0; i <= searchBuffer.length - 4; i++) {
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

    /**
     * ループ内での状態をカプセル化し、メソッド抽出を可能にする軽量レコード
     */
    private static class PixelWriterContext {
        final IntBuffer buffer;
        final FFmpegFrameRecorder recorder;
        final Frame frame;
        final int maxPixelsCapacity;
        int currentPixelIndex = 0;

        PixelWriterContext(IntBuffer buffer, FFmpegFrameRecorder recorder, Frame frame, int maxPixelsCapacity) {
            this.buffer = buffer;
            this.recorder = recorder;
            this.frame = frame;
            this.maxPixelsCapacity = maxPixelsCapacity;
        }
    }
}
