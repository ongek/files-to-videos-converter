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

    private static final Logger log = LogManager.getLogger(FilesToVideosTransformerTask.class);
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
        
        // BytesUtils から正確な色空間の値を取得 (0 -> 白, 1 -> 黒)
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
                
                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec); 
                
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                videoRecorder.setVideoOption("f", "rawvideo");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setVideoBitrate(8000000);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.start();

                // 4バイト (int) × 全ピクセル数分のダイレクトバッファ
                final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
                final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
                
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.image[0] = reusableByteBuffer;

                int aByte;
                int localTempRowIndex = 0;
                PixelWriterContext ctx = new PixelWriterContext(localBuffer, videoRecorder, reusableFrame, maxPixelsCapacity);

                while ((aByte = inputStream.read()) >= 0) {
                    for (int shift = 7; shift >= 0; shift--) {
                        localTempRow[localTempRowIndex++] = ((aByte & (1 << shift)) != 0) ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            writeRowToBuffer(localTempRow, localTempRowLength, localRowCache, duplicateFactor, ctx, taskStatistics);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾の不完全ビットブロックのフラッシュ (正確に pixelZero でパディング)
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, pixelZero);
                    writeRowToBuffer(localTempRow, localTempRowLength, localRowCache, duplicateFactor, ctx, taskStatistics);
                }

                // 最終フレームの残余領域をクリアしてフラッシュ
                if (ctx.currentPixelIndex > 0) {
                    localBuffer.position(ctx.currentPixelIndex);
                    int remainingInts = maxPixelsCapacity - ctx.currentPixelIndex;
                    
                    int zerosWritten = 0;
                    while (zerosWritten < remainingInts) {
                        int chunk = Math.min(remainingInts - zerosWritten, imgWidth);
                        Arrays.fill(localRowCache, 0, chunk, pixelZero);
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

    private void writeRowToBuffer(int[] localTempRow, int tempRowLength, int[] localRowCache, 
                                  int duplicateFactor, PixelWriterContext ctx, 
                                  io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) throws Exception {
        int cacheIdx = 0;
        for (int i = 0; i < tempRowLength; i++) {
            final int px = localTempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            // バッファの上限を超えたら即座に動画へ書き込み（レコード）
            if (ctx.currentPixelIndex + localRowCache.length > ctx.maxPixelsCapacity) {
                ctx.buffer.rewind(); 
                ctx.recorder.record(ctx.frame, AV_PIX_FMT_RGB32_1);
                stats.poll();
                ctx.currentPixelIndex = 0;
                
                // 【超重要】書き込み後はバッファのインデックス位置を先頭に戻す
                ctx.buffer.position(0);
            }
            
            ctx.buffer.position(ctx.currentPixelIndex);
            ctx.buffer.put(localRowCache, 0, localRowCache.length);
            ctx.currentPixelIndex += localRowCache.length;
        }
    }

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
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC.", e);
        }
    }

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
