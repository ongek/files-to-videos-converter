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

    private static final int IO_BUFFER_SIZE = 131072; // 128KB 物理セクタ最適化バッファ

    public FilesToVideosTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        
        // スレッドセーフ化：インスタンスフィールドを排除しローカルに拘束
        final int localLastZeroBytesCount = fileUtils.calculateLastZeroBytesAmount(processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        // JITのレジスタ割り当てを最大化するためのfinalローカル変数化
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

                // 1. レコーダーのセットアップと起動（ここでJavaCPPのJNIロードが確実に完了する）
                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));
                // GitHub Actions（環境変数 GITHUB_ACTIONS が存在する環境）なら、ソフトウェアエンコーダ（libx265）に切り替える
                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";

                videoRecorder.setVideoCodecName(activeCodec); 
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                videoRecorder.setVideoOption("f", "rawvideo");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setVideoBitrate(8000000);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.start();

                // 2. ネイティブロード完了後に安全にフレームバッファを構築（クラッシュ防止）
                final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
                final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
                
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                int aByte;
                int localTempRowIndex = 0;
                int currentPixelIndex = 0;

              // 【ゼロアロケーション】
                while ((aByte = inputStream.read()) >= 0) {
                    // shiftを7から0へ（MSBから順に処理）
                    for (int shift = 7; shift >= 0; shift--) {
                        
                        // 【修正】右シフトではなく、左シフトマスクで状態を確実に抽出
                        // aByteの「shift」番目のビットが1かどうかをノーボクシングで判定
                        localTempRow[localTempRowIndex++] = ((aByte & (1 << shift)) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            // 横方向の高速展開（SIMD自動ベクトル化を促すフラット書き込み）
                            int cacheIdx = 0;
                            for (int i = 0; i < localTempRowLength; i++) {
                                final int px = localTempRow[i];
                                for (int f = 0; f < duplicateFactor; f++) {
                                    localRowCache[cacheIdx++] = px;
                                }
                            }

                            // 縦方向の複製処理（境界チェック内包）
                            for (int r = 0; r < duplicateFactor; r++) {
                                if (currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                                    // ネイティブへ引き渡す前に、バッファのポインタを完全に先頭へリセット
                                    localBuffer.rewind(); 
                                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                                    taskStatistics.poll();
                                    currentPixelIndex = 0;
                                }
                                
                                localBuffer.position(currentPixelIndex);
                                localBuffer.put(localRowCache, 0, localRowCacheLength);
                                currentPixelIndex += localRowCacheLength;
                            }
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾の不完全ビットブロックのフラッシュ
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    int cacheIdx = 0;
                    for (int i = 0; i < localTempRowLength; i++) {
                        final int px = localTempRow[i];
                        for (int f = 0; f < duplicateFactor; f++) {
                            localRowCache[cacheIdx++] = px;
                        }
                    }
                    for (int r = 0; r < duplicateFactor; r++) {
                        if (currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                            localBuffer.position(0);
                            videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                            taskStatistics.poll();
                            currentPixelIndex = 0;
                        }
                        localBuffer.position(currentPixelIndex);
                        localBuffer.put(localRowCache, 0, localRowCacheLength);
                        currentPixelIndex += localRowCacheLength;
                    }
                }

                // 最終フレームの残余領域のゼロパディング
                if (currentPixelIndex > 0 && currentPixelIndex < maxPixelsCapacity) {
                    localBuffer.position(currentPixelIndex);
                    int remainingInts = maxPixelsCapacity - currentPixelIndex;
                    
                    int zerosWritten = 0;
                    while (zerosWritten < remainingInts) {
                        int chunk = Math.min(remainingInts - zerosWritten, localRowCacheLength);
                        Arrays.fill(localRowCache, 0, chunk, ZERO);
                        localBuffer.put(localRowCache, 0, chunk);
                        zerosWritten += chunk;
                    }
                    
                    localBuffer.position(0);
                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                    taskStatistics.poll();
                }
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        // 最も堅牢で互換性の高い高速コンテナパッチ
        if (resultVideoFile != null) {
            convertHev1ToHvc1(resultVideoFile);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    /**
     * 高速インプレース FourCC パッチ (hev1 -> hvc1)
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            long length = raf.length();
            byte[] buffer = new byte[4];
            long maxSearchBytes = Math.min(length - 4, 4096); 
            
            for (long i = 0; i < maxSearchBytes; i++) {
                raf.seek(i);
                raf.readFully(buffer);
                
                // 「hev1」（0x68, 0x65, 0x76, 0x31）の検出
                if (buffer[0] == 0x68 && buffer[1] == 0x65 && buffer[2] == 0x76 && buffer[3] == 0x31) {
                    // 「hvc1」（0x68, 0x76, 0x63, 0x31）へ置換
                    raf.seek(i);
                    raf.write(new byte[]{0x68, 0x76, 0x63, 0x31});
                    
                    log.info("Successfully patched MP4 container FourCC from 'hev1' to 'hvc1' for: {}", mp4File.getName());
                    break; 
                }
            }
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC metadata directly.", e);
        }
    }
}
