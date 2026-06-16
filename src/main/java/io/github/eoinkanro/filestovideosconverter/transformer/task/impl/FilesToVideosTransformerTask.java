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
                videoRecorder.setVideoOption("f", "rawvideo");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setVideoOption("q:v", "90");
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.start();

                final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
                final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
                
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                int aByte;
                int localTempRowIndex = 0;
                int currentPixelIndex = 0;

                while ((aByte = inputStream.read()) >= 0) {
                    for (int shift = 7; shift >= 0; shift--) {
                        localTempRow[localTempRowIndex++] = ((aByte & (1 << shift)) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            // 横展開の最適化
                            int cacheIdx = 0;
                            for (int i = 0; i < localTempRowLength; i++) {
                                final int px = localTempRow[i];
                                for (int f = 0; f < duplicateFactor; f++) {
                                    localRowCache[cacheIdx++] = px;
                                }
                            }

                            // 縦展開とネイティブ転送
                            for (int r = 0; r < duplicateFactor; r++) {
                                if (currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
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

                // 末尾の不完全ビットブロックのフラッシュ（安全なゼロパディングを統合）
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
                            localBuffer.rewind();
                            videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                            taskStatistics.poll();
                            currentPixelIndex = 0;
                        }
                        localBuffer.position(currentPixelIndex);
                        localBuffer.put(localRowCache, 0, localRowCacheLength);
                        currentPixelIndex += localRowCacheLength;
                    }
                }

                // 最終フレームの残余領域のゼロパディング & 最終書き込み
                if (currentPixelIndex > 0) {
                    if (currentPixelIndex < maxPixelsCapacity) {
                        localBuffer.position(currentPixelIndex);
                        int remainingInts = maxPixelsCapacity - currentPixelIndex;
                        
                        int zerosWritten = 0;
                        while (zerosWritten < remainingInts) {
                            int chunk = Math.min(remainingInts - zerosWritten, localRowCacheLength);
                            Arrays.fill(localRowCache, 0, chunk, ZERO);
                            localBuffer.put(localRowCache, 0, chunk);
                            zerosWritten += chunk;
                        }
                    }
                    
                    localBuffer.rewind(); // 明示的にポインタを先頭へ
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
     * 高速インプレース FourCC パッチ (メモリバッファ一括スキャン版：Wappa最適化)
     */
     private void convertHev1ToHvc1(File mp4File) {
     if (mp4File == null || !mp4File.exists()) return;

     try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
         // MP4のメタデータは通常ファイルの比較的初期にあるが、念のため64KBまで探索
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
}
