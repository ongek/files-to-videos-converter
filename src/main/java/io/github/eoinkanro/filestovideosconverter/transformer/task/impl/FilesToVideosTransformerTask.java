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
import java.nio.channels.FileChannel;
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

        // CLI 引数のロード
        final int imgWidth = inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH);
        final int imgHeight = inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT);
        final int duplicateFactor = inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR);

        // 128B アライメントされたストライドと総フレームサイズ計算
        final int rawRowBytes = imgWidth * 4; // RGBA 4 bytes
        final long alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long totalFrameBytes = alignedRowBytes * imgHeight;

        // 1byte -> 8pixels 高速フラットLUT (1次元配列でL1キャッシュ局所性を最大化)
        final int[] bitToPixelFlatLut = buildBitToPixelFlatLut();

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 FileInputStream inputStream = new FileInputStream(processData);
                 FFmpegFrameRecorder videoRecorder = createConfiguredRecorder(resultVideoFile, imgWidth, imgHeight)) {

                videoRecorder.start();

                // フレーム生成・フラッシュ用ステートフルクラス
                final NativeFrameWriter frameWriter = new NativeFrameWriter(
                        arena, videoRecorder, imgWidth, imgHeight, duplicateFactor, alignedRowBytes, totalFrameBytes
                );

                final int localTempRowLength = imgWidth / duplicateFactor;
                final int[] localTempRow = new int[localTempRowLength];
                final byte[] readBuffer = new byte[IO_BUFFER_SIZE];
                int localTempRowIndex = 0;
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        final int lutOffset = (readBuffer[i] & 0xFF) * 8;
                        System.arraycopy(bitToPixelFlatLut, lutOffset, localTempRow, localTempRowIndex, 8);
                        localTempRowIndex += 8;

                        if (localTempRowIndex >= localTempRowLength) {
                            frameWriter.writeRow(localTempRow);
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 行バッファの残余ゼロパディング
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    frameWriter.writeRow(localTempRow);
                }

                // フレームバッファの残り行をパディングして最終フラッシュ
                frameWriter.flushFinalFrame();
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        if (resultVideoFile != null) {
            convertHev1ToHvc1Fast(resultVideoFile);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    /**
     * レコーダーの構築とパラメータ設定（責任の分離）
     */
    private FFmpegFrameRecorder createConfiguredRecorder(File targetFile, int width, int height) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(targetFile, width, height);
        recorder.setFormat("mp4");
        recorder.setOption("movflags", "faststart");
        recorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

        String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
        recorder.setVideoCodecName(activeCodec);
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);

        // 品質ベースVBR (CRF相当)
        recorder.setVideoBitrate(0);
        recorder.setVideoQuality(60);

        if ("hevc_videotoolbox".equals(activeCodec)) {
            recorder.setVideoOption("prio_speed", "0");       // 圧縮効率最優先
            recorder.setVideoOption("power_efficient", "0");  // フルパワー稼働
            recorder.setVideoOption("realtime", "0");         // 徹底圧縮
            recorder.setVideoOption("spatial_aq", "0");       // ドット輪郭保護
        } else {
            recorder.setVideoOption("preset", "ultrafast");
        }

        recorder.setMaxBFrames(0);
        return recorder;
    }

    /**
     * 1次元フラットLUTの構築（256個のオブジェクト割当をゼロ化）
     */
    private int[] buildBitToPixelFlatLut() {
        final int[] lut = new int[256 * 8];
        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        for (int b = 0; b < 256; b++) {
            for (int bit = 0; bit < 8; bit++) {
                lut[b * 8 + bit] = ((b & (1 << (7 - bit))) != 0) ? pixelOne : pixelZero;
            }
        }
        return lut;
    }

    /**
     * メモリマップ（mmap）を使用したゼロコピー FourCC パッチ ('hev1' -> 'hvc1')
     */
    private void convertHev1ToHvc1Fast(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        final long searchLimit = Math.min(mp4File.length(), 2 * 1024 * 1024L); // 2MB
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw");
             FileChannel channel = raf.getChannel()) {

            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, searchLimit, Arena.ofConfined());
            for (long i = 0; i <= searchLimit - 4; i++) {
                if (segment.get(ValueLayout.JAVA_BYTE, i)     == 0x68 && // 'h'
                    segment.get(ValueLayout.JAVA_BYTE, i + 1) == 0x65 && // 'e'
                    segment.get(ValueLayout.JAVA_BYTE, i + 2) == 0x76 && // 'v'
                    segment.get(ValueLayout.JAVA_BYTE, i + 3) == 0x31) { // '1'

                    segment.set(ValueLayout.JAVA_BYTE, i + 1, (byte) 'v');
                    segment.set(ValueLayout.JAVA_BYTE, i + 2, (byte) 'c');
                    return;
                }
            }
            log.warn("FourCC 'hev1' not patched. Compatibility may be affected.");
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC.", e);
        }
    }

    /**
     * ネイティブフレームの構築と書き出しを行う内部ヘルパークラス
     */
    private class NativeFrameWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytes;
        private final MemorySegment nativePixelSegment;
        private final Frame reusableFrame;
        private final int[] localRowCache;
        private int currentRowInFrame = 0;

        public NativeFrameWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                 int imgWidth, int imgHeight, int duplicateFactor,
                                 long alignedRowBytes, long totalFrameBytes) {
            this.videoRecorder = videoRecorder;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytes = alignedRowBytes;
            this.localRowCache = new int[imgWidth];

            this.nativePixelSegment = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
            this.reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
            this.reusableFrame.imageStride = (int) alignedRowBytes;
            this.reusableFrame.image[0] = this.nativePixelSegment.asByteBuffer();
        }

        public void writeRow(int[] localTempRow) throws Exception {
            if (duplicateFactor == 1) {
                ensureFrameCapacity();
                long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
                MemorySegment.copy(localTempRow, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, imgWidth);
                currentRowInFrame++;
                return;
            }

            // 横方向拡大
            int cacheIdx = 0;
            for (int px : localTempRow) {
                for (int f = 0; f < duplicateFactor; f++) {
                    localRowCache[cacheIdx++] = px;
                }
            }

            // 縦方向拡大: 1行目をNativeに転送し、2行目以降はNative-to-Native高速コピー(memcpy)
            ensureFrameCapacity();
            long firstRowOffset = currentRowInFrame * alignedRowBytes;
            MemorySegment.copy(localRowCache, 0, nativePixelSegment, ValueLayout.JAVA_INT, firstRowOffset, imgWidth);
            currentRowInFrame++;

            long rowSizeBytes = (long) imgWidth * 4;
            MemorySegment firstRowSlice = nativePixelSegment.asSlice(firstRowOffset, rowSizeBytes);

            for (int r = 1; r < duplicateFactor; r++) {
                ensureFrameCapacity();
                long nextRowOffset = currentRowInFrame * alignedRowBytes;
                nativePixelSegment.asSlice(nextRowOffset, rowSizeBytes).copyFrom(firstRowSlice);
                currentRowInFrame++;
            }
        }

        private void ensureFrameCapacity() throws Exception {
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                taskStatistics.poll();
                currentRowInFrame = 0;
            }
        }

        public void flushFinalFrame() throws Exception {
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
    }
}
