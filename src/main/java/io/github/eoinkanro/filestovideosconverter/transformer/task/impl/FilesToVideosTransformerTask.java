package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.task.AbstractTransformerTask;
import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArgument.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Slf4j
public class FilesToVideosTransformerTask extends AbstractTransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024; // 1MB ディスク一括バッファ
    private static final long M4_CACHE_LINE_ALIGNMENT = 128L; // Apple M4 キャッシュライン (128 bytes)
    private static final byte PADDING_BYTE = (byte) 0xFF;

    public FilesToVideosTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);

        final int localLastZeroBytesCount = fileUtils.calculateLastZeroBytesAmount(processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        // CLI 引数のロード (-vw, -vh, -df, -fr)
        final int imgWidth = inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH);
        final int imgHeight = inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT);
        final int duplicateFactor = inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR);
        final int frameRate = inputCLIArgumentsHolder.getArgument(FRAMERATE);

        // --- 128B キャッシュライン・パディング計算 ---
        final int rawRowBytes = imgWidth * 4; // RGBA 4 bytes
        final long alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long totalFrameBytes = alignedRowBytes * imgHeight;

        final int tempRowLength = imgWidth / duplicateFactor;
        final int[] tempRow = new int[tempRowLength];
        final int[] rowCache = new int[imgWidth];

        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        // 1byte -> 8pixels 高速LUT (事前展開テーブル)
        final int[][] bitToPixelLut = new int[256][8];
        for (int b = 0; b < 256; b++) {
            for (int bit = 0; bit < 8; bit++) {
                bitToPixelLut[b][bit] = ((b & (1 << (7 - bit))) != 0) ? pixelOne : pixelZero;
            }
        }

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            // 【Java 26 FFM API】Arena でネイティブメモリのライフサイクルを自動管理 (GCゼロ)
            try (Arena arena = Arena.ofConfined();
                 FileInputStream inputStream = new FileInputStream(processData);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                // 【Apple M4 最適化】パディング込みのフレーム総サイズを 128B アライメントで確保
                final MemorySegment nativePixelSegment = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
                final ByteBuffer reusableByteBuffer = nativePixelSegment.asByteBuffer();

                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(frameRate);

                String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
                videoRecorder.setVideoCodecName(activeCodec);
                videoRecorder.setPixelFormat(AV_PIX_FMT_YUV420P);

                // エンコードパラメータ設定
                videoRecorder.setVideoQuality(90);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setMaxBFrames(0);
                videoRecorder.setOption("bf", "0");

                videoRecorder.start();

                // imageStride に alignedRowBytes (128B境界) を指定
                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                reusableFrame.imageStride = (int) alignedRowBytes;
                reusableFrame.image[0] = reusableByteBuffer;

                int currentRowInFrame = 0;
                int tempRowIndex = 0;

                final byte[] readBuffer = new byte[IO_BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int[] px8 = bitToPixelLut[readBuffer[i] & 0xFF];
                        System.arraycopy(px8, 0, tempRow, tempRowIndex, 8);
                        tempRowIndex += 8;

                        if (tempRowIndex >= tempRowLength) {
                            currentRowInFrame = flushRowToSegment(
                                    tempRow, tempRowLength, rowCache, imgWidth, duplicateFactor,
                                    imgHeight, alignedRowBytes, nativePixelSegment, videoRecorder,
                                    reusableFrame, currentRowInFrame
                            );
                            tempRowIndex = 0;
                        }
                    }
                }

                // 行バッファの残りをゼロ埋めしてフラッシュ
                if (tempRowIndex > 0) {
                    Arrays.fill(tempRow, tempRowIndex, tempRowLength, 0);
                    currentRowInFrame = flushRowToSegment(
                            tempRow, tempRowLength, rowCache, imgWidth, duplicateFactor,
                            imgHeight, alignedRowBytes, nativePixelSegment, videoRecorder,
                            reusableFrame, currentRowInFrame
                    );
                }

                // フレームバッファの残りの行を一括パディングしてフラッシュ
                if (currentRowInFrame > 0) {
                    if (currentRowInFrame < imgHeight) {
                        long offsetBytes = currentRowInFrame * alignedRowBytes;
                        long lengthBytes = (imgHeight - currentRowInFrame) * alignedRowBytes;
                        nativePixelSegment.asSlice(offsetBytes, lengthBytes).fill(PADDING_BYTE);
                    }
                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
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
     * 128B アライメントされた各行アドレスへピクセルを展開・記録する
     */
    private int flushRowToSegment(int[] tempRow, int tempRowLength, int[] rowCache, int imgWidth,
                                  int duplicateFactor, int imgHeight, long alignedRowBytes,
                                  MemorySegment nativePixelSegment, FFmpegFrameRecorder videoRecorder,
                                  Frame reusableFrame, int currentRowInFrame) throws Exception {

        if (duplicateFactor == 1) {
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                currentRowInFrame = 0;
            }

            long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
            MemorySegment.copy(tempRow, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, imgWidth);
            return currentRowInFrame + 1;
        }

        // 横拡大
        int cacheIdx = 0;
        for (int i = 0; i < tempRowLength; i++) {
            final int px = tempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                rowCache[cacheIdx++] = px;
            }
        }

        // 縦拡大 (各行が alignedRowBytes ごとの 128B アライメント位置に配置される)
        for (int r = 0; r < duplicateFactor; r++) {
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                currentRowInFrame = 0;
            }

            long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
            MemorySegment.copy(rowCache, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, imgWidth);
            currentRowInFrame++;
        }

        return currentRowInFrame;
    }

    /**
     * 高速インプレース FourCC パッチ ('hev1' -> 'hvc1')
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        final int searchLimit = 2 * 1024 * 1024; // 2MB
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw")) {
            final int searchSize = (int) Math.min(raf.length(), searchLimit);
            final byte[] searchBuffer = new byte[searchSize];
            raf.readFully(searchBuffer);

            for (int i = 0; i <= searchSize - 4; i++) {
                if (searchBuffer[i] == 'h' && searchBuffer[i + 1] == 'e' &&
                    searchBuffer[i + 2] == 'v' && searchBuffer[i + 3] == '1') {
                    raf.seek(i);
                    raf.write(new byte[]{'h', 'v', 'c', '1'});
                    return;
                }
            }
            log.warn("FourCC 'hev1' not patched. Compatibility may be affected.");
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC.", e);
        }
    }
}
