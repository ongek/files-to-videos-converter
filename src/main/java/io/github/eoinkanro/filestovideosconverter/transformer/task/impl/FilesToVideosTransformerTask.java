package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static io.github.eoinkanro.filestovideosconverter.utils.BytesUtils.ZERO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024; // 1MB ディスク一括読み込みバッファ
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

        // --- 【Apple M4 最適化】128B imageStride 計算 ---
        final int rawRowBytes = imgWidth * 4; // RGBA 4 bytes
        // 128バイト境界への切り上げ: (rawRowBytes + 127) & ~127
        final long alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long totalFrameBytes = alignedRowBytes * imgHeight;

        final int localTempRowLength = imgWidth / duplicateFactor;
        final int[] localTempRow = new int[localTempRowLength];
        final int[] localRowCache = new int[imgWidth];

        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        // 1byte -> 8pixels 高速LUT (事前展開テーブルで分岐予測ミスをゼロ化)
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

                // パディングを含む1フレーム総サイズを 128B アライメントで確保
                final MemorySegment nativePixelSegment = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
                final ByteBuffer reusableByteBuffer = nativePixelSegment.asByteBuffer();

// ==================== 基本フォーマット ====================
videoRecorder.setFormat("mp4");
videoRecorder.setOption("movflags", "faststart");
videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

// ==================== コーデック指定 ====================
String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
videoRecorder.setVideoCodecName(activeCodec);
videoRecorder.setPixelFormat(AV_PIX_FMT_YUV420P);

// ==================== CRF / 品質ベースVBR の設定 ====================
// ビットレート固定を明示的に解除 (0 = 品質モードを優先)
videoRecorder.setVideoBitrate(0);

// 品質スケール設定 (0〜100) -> 60 は圧縮率と復元精度の黄金比
videoRecorder.setVideoQuality(60); 

if ("hevc_videotoolbox".equals(activeCodec)) {
    // M4 ハードウェアをフルパワー＆圧縮優先で動かす
    videoRecorder.setVideoOption("prio_speed", "0");       // 圧縮効率最優先
    videoRecorder.setVideoOption("power_efficient", "0");  // フルパワー稼働
    videoRecorder.setVideoOption("realtime", "0");         // 徹底圧縮
    videoRecorder.setVideoOption("spatial_aq", "0");       // ドットの輪郭を保持
} else {
    // CI (GitHub Actions) 向け
    videoRecorder.setVideoOption("preset", "ultrafast");
}

videoRecorder.setMaxBFrames(0);

videoRecorder.start();

                final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                // FFmpeg linesize[0] に 128B ストライドを明示
                reusableFrame.imageStride = (int) alignedRowBytes;
                reusableFrame.image[0] = reusableByteBuffer;

                int currentRowInFrame = 0;
                int localTempRowIndex = 0;

                final byte[] readBuffer = new byte[IO_BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int[] px8 = bitToPixelLut[readBuffer[i] & 0xFF];
                        System.arraycopy(px8, 0, localTempRow, localTempRowIndex, 8);
                        localTempRowIndex += 8;

                        if (localTempRowIndex >= localTempRowLength) {
                            currentRowInFrame = flushRowToSegment(
                                    localTempRow, localTempRowLength, localRowCache, imgWidth,
                                    duplicateFactor, imgHeight, alignedRowBytes, nativePixelSegment,
                                    videoRecorder, reusableFrame, currentRowInFrame
                            );
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 行バッファの残余ゼロパディング処理
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    currentRowInFrame = flushRowToSegment(
                            localTempRow, localTempRowLength, localRowCache, imgWidth,
                            duplicateFactor, imgHeight, alignedRowBytes, nativePixelSegment,
                            videoRecorder, reusableFrame, currentRowInFrame
                    );
                }

                // フレームバッファの残りの行を一括パディングしてVRAM/エンコーダへフラッシュ
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
     * 各行を 128B アライメントされたストライド位置へ直接コピー
     */
    private int flushRowToSegment(int[] localTempRow, int localTempRowLength,
                                  int[] localRowCache, int imgWidth,
                                  int duplicateFactor, int imgHeight,
                                  long alignedRowBytes,
                                  MemorySegment nativePixelSegment,
                                  FFmpegFrameRecorder videoRecorder,
                                  Frame reusableFrame,
                                  int currentRowInFrame) throws Exception {

        // --- FAST PATH: duplicateFactor == 1 ---
        if (duplicateFactor == 1) {
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                currentRowInFrame = 0;
            }

            long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
            MemorySegment.copy(localTempRow, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, imgWidth);
            return currentRowInFrame + 1;
        }

        // --- SLOW PATH: duplicateFactor > 1 (横・縦拡大) ---
        int cacheIdx = 0;
        for (int i = 0; i < localTempRowLength; i++) {
            final int px = localTempRow[i];
            for (int f = 0; f < duplicateFactor; f++) {
                localRowCache[cacheIdx++] = px;
            }
        }

        for (int r = 0; r < duplicateFactor; r++) {
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                currentRowInFrame = 0;
            }

            long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
            MemorySegment.copy(localRowCache, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, imgWidth);
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
                if (searchBuffer[i] == 0x68 && searchBuffer[i + 1] == 0x65 &&
                    searchBuffer[i + 2] == 0x76 && searchBuffer[i + 3] == 0x31) { // 'h' 'e' 'v' '1'
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
