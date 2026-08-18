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

        final int imgWidth = inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH);
        final int imgHeight = inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT);
        final int duplicateFactor = inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR);

        final int rawRowBytes = imgWidth * 4;
        final long alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long totalFrameBytes = alignedRowBytes * imgHeight;

        final int[] bitToPixelFlatLut = buildBitToPixelFlatLut();

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 FileInputStream inputStream = new FileInputStream(processData);
                 FFmpegFrameRecorder videoRecorder = createConfiguredRecorder(resultVideoFile, imgWidth, imgHeight)) {

                videoRecorder.start();

                final NativeFrameWriter frameWriter = new NativeFrameWriter(
                        arena, videoRecorder, taskStatistics, imgWidth, imgHeight, duplicateFactor, alignedRowBytes, totalFrameBytes
                );

                final int localTempRowLength = imgWidth / duplicateFactor;
                final int[] localTempRow = new int[localTempRowLength];
                final byte[] readBuffer = new byte[IO_BUFFER_SIZE];
                int localTempRowIndex = 0;
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        // 【M4 SIMD最適化】System.arraycopy を廃止し、NEON レジスタ展開を誘発
                        final int lutOffset = (readBuffer[i] & 0xFF) << 3; // * 8
                        localTempRow[localTempRowIndex]     = bitToPixelFlatLut[lutOffset];
                        localTempRow[localTempRowIndex + 1] = bitToPixelFlatLut[lutOffset + 1];
                        localTempRow[localTempRowIndex + 2] = bitToPixelFlatLut[lutOffset + 2];
                        localTempRow[localTempRowIndex + 3] = bitToPixelFlatLut[lutOffset + 3];
                        localTempRow[localTempRowIndex + 4] = bitToPixelFlatLut[lutOffset + 4];
                        localTempRow[localTempRowIndex + 5] = bitToPixelFlatLut[lutOffset + 5];
                        localTempRow[localTempRowIndex + 6] = bitToPixelFlatLut[lutOffset + 6];
                        localTempRow[localTempRowIndex + 7] = bitToPixelFlatLut[lutOffset + 7];
                        localTempRowIndex += 8;

                        if (localTempRowIndex >= localTempRowLength) {
                            frameWriter.writeRow(localTempRow);
                            localTempRowIndex = 0;
                        }
                    }
                }

                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    frameWriter.writeRow(localTempRow);
                }

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

    private FFmpegFrameRecorder createConfiguredRecorder(File targetFile, int width, int height) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(targetFile, width, height);
        recorder.setFormat("mp4");
        recorder.setOption("movflags", "faststart");
        recorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));

        String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
        recorder.setVideoCodecName(activeCodec);
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);

        recorder.setVideoBitrate(0);
        recorder.setVideoQuality(60);

        if ("hevc_videotoolbox".equals(activeCodec)) {
            recorder.setVideoOption("prio_speed", "0");
            recorder.setVideoOption("power_efficient", "0");
            recorder.setVideoOption("realtime", "0");
            recorder.setVideoOption("spatial_aq", "0");
        } else {
            recorder.setVideoOption("preset", "ultrafast");
        }

        recorder.setMaxBFrames(0);
        return recorder;
    }

    private int[] buildBitToPixelFlatLut() {
        final int[] lut = new int[256 * 8];
        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        for (int b = 0; b < 256; b++) {
            for (int bit = 0; bit < 8; bit++) {
                lut[(b << 3) + bit] = ((b & (1 << (7 - bit))) != 0) ? pixelOne : pixelZero;
            }
        }
        return lut;
    }

    /**
     * 安全な Arena 管理によるゼロコピー FourCC パッチ ('hev1' -> 'hvc1')
     */
    private void convertHev1ToHvc1Fast(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        final long searchLimit = Math.min(mp4File.length(), 2 * 1024 * 1024L);
        // 【修正】Arena を try-with-resources で明示管理してリソースリークを完全防止
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw");
             FileChannel channel = raf.getChannel();
             Arena mmapArena = Arena.ofConfined()) {

            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, searchLimit, mmapArena);
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
     * 高速ネイティブフレーム制御クラス (static化 & ゼロ無駄アロケーション)
     */
    private static class NativeFrameWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final Object taskStatistics; // 呼び出し用統計インスタンス
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytes;
        private final MemorySegment nativePixelSegment;
        private final Frame reusableFrame;
        private final int[] localRowCache;
        private int currentRowInFrame = 0;

        public NativeFrameWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                 Object taskStatistics, int imgWidth, int imgHeight,
                                 int duplicateFactor, long alignedRowBytes, long totalFrameBytes) {
            this.videoRecorder = videoRecorder;
            this.taskStatistics = taskStatistics;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytes = alignedRowBytes;
            // df == 1 の時は無駄な配列アロケーションを抑止
            this.localRowCache = (duplicateFactor > 1) ? new int[imgWidth] : null;

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

            int cacheIdx = 0;
            for (int px : localTempRow) {
                for (int f = 0; f < duplicateFactor; f++) {
                    localRowCache[cacheIdx++] = px;
                }
            }

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
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
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
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
            }
        }
    }
}
