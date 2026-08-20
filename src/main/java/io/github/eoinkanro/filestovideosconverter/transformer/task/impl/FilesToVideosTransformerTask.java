package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import io.github.eoinkanro.filestovideosconverter.utils.BytesUtils;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final long M4_CACHE_LINE_ALIGNMENT = 128L; // Apple M4 キャッシュライン (128 bytes)
    private static final byte PADDING_BYTE = (byte) 0xFF;

    // 【M4 L1D キャッシュ常駐 LUT (32KB)】df=4 用の 256パターン × 16 long (128B) テーブル
    // 分岐 2億4000万回をゼロにし、128B キャッシュライン単位で一撃ストア
    private static final long[] DF4_CACHE_LINE_LUT = new long[256 * 16];

    static {
        long black = 0xFF000000_FF000000L;
        long white = 0xFFFFFFFF_FFFFFFFFL;
        for (int b = 0; b < 256; b++) {
            int base = b << 4; // * 16
            for (int bit = 7; bit >= 0; bit--) {
                long pair = ((b & (1 << bit)) != 0) ? black : white;
                int offset = (7 - bit) << 1; // * 2
                DF4_CACHE_LINE_LUT[base + offset]     = pair;
                DF4_CACHE_LINE_LUT[base + offset + 1] = pair;
            }
        }
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

        final int rawRowBytes = imgWidth * 4;
        final long alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long totalFrameBytes = alignedRowBytes * imgHeight;

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 RandomAccessFile raf = new RandomAccessFile(processData, "r");
                 FileChannel fileChannel = raf.getChannel();
                 FFmpegFrameRecorder videoRecorder = createConfiguredRecorder(resultVideoFile, imgWidth, imgHeight)) {

                final long fileSize = processData.length();
                final MemorySegment inputMappedSegment = (fileSize > 0)
                        ? fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena)
                        : MemorySegment.NULL;

                videoRecorder.start();

                final NativeFrameStreamWriter frameWriter = new NativeFrameStreamWriter(
                        arena, videoRecorder, taskStatistics, imgWidth, imgHeight, duplicateFactor, alignedRowBytes, totalFrameBytes
                );

                // 【行単位バッチストリーミング】1バイトずつではなく、1行分を一気に高速処理
                final int bytesPerBlockRow = imgWidth / (duplicateFactor << 3); // 1行に必要な入力バイト数 (df=4なら40B)
                long inputOffset = 0;

                while (inputOffset + bytesPerBlockRow <= fileSize) {
                    frameWriter.writeFullRow(inputMappedSegment, inputOffset, bytesPerBlockRow);
                    inputOffset += bytesPerBlockRow;
                }

                // 端数バイト（ファイルの末尾）の処理
                while (inputOffset < fileSize) {
                    byte b = inputMappedSegment.get(ValueLayout.JAVA_BYTE, inputOffset++);
                    frameWriter.writeSingleByte(b);
                }

                frameWriter.finish();
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

        final int videoQuality = inputCLIArgumentsHolder.getArgument(VIDEO_QUALITY);
        recorder.setVideoBitrate(0);
        recorder.setVideoQuality(videoQuality);

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

    private void convertHev1ToHvc1Fast(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        final long searchLimit = Math.min(mp4File.length(), 2 * 1024 * 1024L);
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw");
             FileChannel channel = raf.getChannel();
             Arena mmapArena = Arena.ofConfined()) {

            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, searchLimit, mmapArena);
            for (long i = 0; i <= searchLimit - 4; i++) {
                if (segment.get(ValueLayout.JAVA_BYTE, i)     == 0x68 &&
                    segment.get(ValueLayout.JAVA_BYTE, i + 1) == 0x65 &&
                    segment.get(ValueLayout.JAVA_BYTE, i + 2) == 0x76 &&
                    segment.get(ValueLayout.JAVA_BYTE, i + 3) == 0x31) {

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
     * 【Direct Native Streamer (完全行ストリーミング版)】
     */
    private static class NativeFrameStreamWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final Object taskStatistics;
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytes;
        private final long rowSizeBytes;
        private final MemorySegment nativePixelSegment;
        private final Frame reusableFrame;

        private int currentRowInFrame = 0;
        private int currentPixelInRow = 0;

        public NativeFrameStreamWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                       Object taskStatistics, int imgWidth, int imgHeight,
                                       int duplicateFactor, long alignedRowBytes, long totalFrameBytes) {
            this.videoRecorder = videoRecorder;
            this.taskStatistics = taskStatistics;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytes = alignedRowBytes;
            this.rowSizeBytes = (long) imgWidth * 4;

            this.nativePixelSegment = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
            this.reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
            this.reusableFrame.imageStride = (int) alignedRowBytes;
            this.reusableFrame.image[0] = this.nativePixelSegment.asByteBuffer();
        }

        /**
         * 【最速パス】1行分（40バイト等）を一括で Native メモリへ展開 (分岐ゼロ・毎行1回のコミット)
         */
        public void writeFullRow(MemorySegment inputSegment, long inputOffset, int bytesCount) throws Exception {
            long writeOffset = (long) currentRowInFrame * alignedRowBytes;

            if (duplicateFactor == 4) {
                // df=4 特化: 32KB L1 キャッシュ LUT から 128B (16 long) ずつ一気に転送 (分岐 0 回)
                for (int i = 0; i < bytesCount; i++) {
                    int val = inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i) & 0xFF;
                    int lutBase = val << 4; // * 16

                    // 128B キャッシュライン一括ストア (手動アンローリング)
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset,      DF4_CACHE_LINE_LUT[lutBase]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 8,  DF4_CACHE_LINE_LUT[lutBase + 1]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 16, DF4_CACHE_LINE_LUT[lutBase + 2]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 24, DF4_CACHE_LINE_LUT[lutBase + 3]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 32, DF4_CACHE_LINE_LUT[lutBase + 4]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 40, DF4_CACHE_LINE_LUT[lutBase + 5]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 48, DF4_CACHE_LINE_LUT[lutBase + 6]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 56, DF4_CACHE_LINE_LUT[lutBase + 7]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 64, DF4_CACHE_LINE_LUT[lutBase + 8]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 72, DF4_CACHE_LINE_LUT[lutBase + 9]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 80, DF4_CACHE_LINE_LUT[lutBase + 10]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 88, DF4_CACHE_LINE_LUT[lutBase + 11]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 96, DF4_CACHE_LINE_LUT[lutBase + 12]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 104,DF4_CACHE_LINE_LUT[lutBase + 13]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 112,DF4_CACHE_LINE_LUT[lutBase + 14]);
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 120,DF4_CACHE_LINE_LUT[lutBase + 15]);

                    writeOffset += 128;
                }
            } else {
                // 汎用パス
                for (int i = 0; i < bytesCount; i++) {
                    writeSingleByte(inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i));
                }
                return;
            }

            commitRow();
        }

        /**
         * 端数バイト用
         */
        public void writeSingleByte(byte b) throws Exception {
            long rowBaseOffset = (long) currentRowInFrame * alignedRowBytes;
            long writeOffset = rowBaseOffset + ((long) currentPixelInRow << 2);
            int val = b & 0xFF;

            if (duplicateFactor == 4) {
                int lutBase = val << 4;
                for (int k = 0; k < 16; k++) {
                    nativePixelSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + (k << 3), DF4_CACHE_LINE_LUT[lutBase + k]);
                }
                currentPixelInRow += 32;
            } else if (duplicateFactor == 1) {
                int lutOffset = val << 3;
                for (int p = 0; p < 8; p++) {
                    nativePixelSegment.set(ValueLayout.JAVA_INT, writeOffset + (p << 2), BytesUtils.BIT_TO_PIXEL_FLAT_LUT[lutOffset + p]);
                }
                currentPixelInRow += 8;
            } else {
                for (int bit = 7; bit >= 0; bit--) {
                    int px = ((val & (1 << bit)) != 0) ? BytesUtils.ONE : BytesUtils.ZERO;
                    for (int f = 0; f < duplicateFactor; f++) {
                        nativePixelSegment.set(ValueLayout.JAVA_INT, writeOffset, px);
                        writeOffset += 4;
                    }
                }
                currentPixelInRow += (duplicateFactor << 3);
            }

            if (currentPixelInRow >= imgWidth) {
                currentPixelInRow = 0;
                commitRow();
            }
        }

        private void commitRow() throws Exception {
            long firstRowOffset = (long) currentRowInFrame * alignedRowBytes;
            currentRowInFrame++;

            // 縦方向拡大 (Native間高速コピー)
            if (duplicateFactor > 1) {
                MemorySegment firstRowSlice = nativePixelSegment.asSlice(firstRowOffset, rowSizeBytes);
                for (int r = 1; r < duplicateFactor; r++) {
                    long nextRowOffset = (long) currentRowInFrame * alignedRowBytes;
                    nativePixelSegment.asSlice(nextRowOffset, rowSizeBytes).copyFrom(firstRowSlice);
                    currentRowInFrame++;
                }
            }

            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_RGBA);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
                currentRowInFrame = 0;
            }
        }

        public void finish() throws Exception {
            if (currentPixelInRow > 0) {
                long writeOffset = (long) currentRowInFrame * alignedRowBytes + ((long) currentPixelInRow << 2);
                long remainingBytes = rowSizeBytes - ((long) currentPixelInRow << 2);
                nativePixelSegment.asSlice(writeOffset, remainingBytes).fill((byte) 0xFF);
                currentPixelInRow = 0;
                commitRow();
            }

            if (currentRowInFrame > 0) {
                if (currentRowInFrame < imgHeight) {
                    long offsetBytes = (long) currentRowInFrame * alignedRowBytes;
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
