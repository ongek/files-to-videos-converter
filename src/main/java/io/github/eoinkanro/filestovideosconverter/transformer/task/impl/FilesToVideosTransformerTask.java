package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final long M4_CACHE_LINE_ALIGNMENT = 128L; // Apple M4 キャッシュライン (128 bytes)
    private static final byte PADDING_BYTE = (byte) 0xFF; // 白パディング (Y=255)

    // 【YUV420P 直結 LUT (8KB)】1バイト -> 横32画素の Y プレーン (32 bytes = 4 long)
    private static final long[] DF4_Y_PLANE_LUT = new long[256 * 4];

    static {
        long blackQuad = 0x00000000_00000000L; // 黒4画素 (Y=0)
        long whiteQuad = 0xFFFFFFFF_FFFFFFFFL; // 白4画素 (Y=255)

        for (int b = 0; b < 256; b++) {
            int base = b << 2;
            for (int pair = 0; pair < 4; pair++) {
                int bit0 = (b >> (7 - (pair * 2))) & 1;
                int bit1 = (b >> (7 - (pair * 2 + 1))) & 1;

                long val0 = (bit0 != 0) ? blackQuad : whiteQuad;
                long val1 = (bit1 != 0) ? blackQuad : whiteQuad;
                DF4_Y_PLANE_LUT[base + pair] = (val1 << 32) | (val0 & 0xFFFFFFFFL);
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

        // YUV420P の連続フレームサイズ計算 (128B アライメント)
        final long alignedRowBytesY = (imgWidth + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long yPlaneBytes = alignedRowBytesY * imgHeight;
        
        final int uvHeight = imgHeight / 2;
        final long alignedRowBytesUV = ((imgWidth / 2) + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long uvPlaneBytes = alignedRowBytesUV * uvHeight;
        final long totalFrameBytes = yPlaneBytes + (uvPlaneBytes * 2); // 連続メモリ総サイズ

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

                // 連続 YUV420P バッファライター
                final ContiguousYUV420PFrameWriter frameWriter = new ContiguousYUV420PFrameWriter(
                        arena, videoRecorder, taskStatistics, imgWidth, imgHeight, duplicateFactor,
                        alignedRowBytesY, yPlaneBytes, totalFrameBytes
                );

                final int bytesPerBlockRow = imgWidth / (duplicateFactor << 3); // df=4 なら 40B
                long inputOffset = 0;

                while (inputOffset + bytesPerBlockRow <= fileSize) {
                    frameWriter.writeFullRow(inputMappedSegment, inputOffset, bytesPerBlockRow);
                    inputOffset += bytesPerBlockRow;
                }

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
            // 【HW速度ブースト】M4 メディアエンジンの速度優先モード (待機時間を短縮)
            recorder.setVideoOption("prio_speed", "1");
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
     * 【連続 YUV420P バッファライター】
     * 1つの連続メモリ領域に Y + U(128) + V(128) を配置し、完全な白黒を出力
     */
    private static class ContiguousYUV420PFrameWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final Object taskStatistics;
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytesY;
        private final long yPlaneBytes;
        private final long rowSizeBytesY;

        private final MemorySegment totalSegment;
        private final Frame reusableFrame;

        private int currentRowInFrame = 0;
        private int currentPixelInRow = 0;

        public ContiguousYUV420PFrameWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                            Object taskStatistics, int imgWidth, int imgHeight,
                                            int duplicateFactor, long alignedRowBytesY, long yPlaneBytes,
                                            long totalFrameBytes) {
            this.videoRecorder = videoRecorder;
            this.taskStatistics = taskStatistics;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytesY = alignedRowBytesY;
            this.yPlaneBytes = yPlaneBytes;
            this.rowSizeBytesY = imgWidth;

            // 1. Y + U + V の連続ネイティブメモリを 1 つ確保
            this.totalSegment = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);

            // 2. U / V プレーン領域（Y領域の直後から末尾まで）を 128 (0x80) で完全初期化 (無色・白黒固定)
            long uvStartOffset = yPlaneBytes;
            long uvTotalBytes = totalFrameBytes - yPlaneBytes;
            this.totalSegment.asSlice(uvStartOffset, uvTotalBytes).fill((byte) 128);

            // 3. 単一の連続 ByteBuffer を Frame.image[0] に直結 (JavaCV の仕様に完全一致)
            this.reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 1);
            this.reusableFrame.imageStride = (int) alignedRowBytesY;
            this.reusableFrame.image = new ByteBuffer[] { this.totalSegment.asByteBuffer() };
        }

        public void writeFullRow(MemorySegment inputSegment, long inputOffset, int bytesCount) throws Exception {
            long writeOffset = (long) currentRowInFrame * alignedRowBytesY;

            if (duplicateFactor == 4) {
                for (int i = 0; i < bytesCount; i++) {
                    int val = inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i) & 0xFF;
                    int lutBase = val << 2;

                    totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset,      DF4_Y_PLANE_LUT[lutBase]);
                    totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 8,  DF4_Y_PLANE_LUT[lutBase + 1]);
                    totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 16, DF4_Y_PLANE_LUT[lutBase + 2]);
                    totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 24, DF4_Y_PLANE_LUT[lutBase + 3]);

                    writeOffset += 32;
                }
            } else {
                for (int i = 0; i < bytesCount; i++) {
                    writeSingleByte(inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i));
                }
                return;
            }

            commitRow();
        }

        public void writeSingleByte(byte b) throws Exception {
            long rowBaseOffset = (long) currentRowInFrame * alignedRowBytesY;
            long writeOffset = rowBaseOffset + currentPixelInRow;
            int val = b & 0xFF;

            if (duplicateFactor == 4) {
                int lutBase = val << 2;
                totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset,      DF4_Y_PLANE_LUT[lutBase]);
                totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 8,  DF4_Y_PLANE_LUT[lutBase + 1]);
                totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 16, DF4_Y_PLANE_LUT[lutBase + 2]);
                totalSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 24, DF4_Y_PLANE_LUT[lutBase + 3]);
                currentPixelInRow += 32;
            } else {
                for (int bit = 7; bit >= 0; bit--) {
                    byte yVal = ((val & (1 << bit)) != 0) ? (byte) 0 : (byte) 255;
                    for (int f = 0; f < duplicateFactor; f++) {
                        totalSegment.set(ValueLayout.JAVA_BYTE, writeOffset++, yVal);
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
            long firstRowOffset = (long) currentRowInFrame * alignedRowBytesY;
            currentRowInFrame++;

            // 縦方向拡大: Yプレーン内 Native ダイレクト高速コピー
            if (duplicateFactor > 1) {
                for (int r = 1; r < duplicateFactor; r++) {
                    long nextRowOffset = (long) currentRowInFrame * alignedRowBytesY;
                    MemorySegment.copy(totalSegment, ValueLayout.JAVA_BYTE, firstRowOffset,
                                       totalSegment, ValueLayout.JAVA_BYTE, nextRowOffset, rowSizeBytesY);
                    currentRowInFrame++;
                }
            }

            // 【完全白黒 YUV420P】sws_scale 完全バイパス
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
                currentRowInFrame = 0;
            }
        }

        public void finish() throws Exception {
            if (currentPixelInRow > 0) {
                long writeOffset = (long) currentRowInFrame * alignedRowBytesY + currentPixelInRow;
                long remainingBytes = rowSizeBytesY - currentPixelInRow;
                totalSegment.asSlice(writeOffset, remainingBytes).fill(PADDING_BYTE);
                currentPixelInRow = 0;
                commitRow();
            }

            if (currentRowInFrame > 0) {
                if (currentRowInFrame < imgHeight) {
                    long offsetBytes = (long) currentRowInFrame * alignedRowBytesY;
                    long lengthBytes = (imgHeight - currentRowInFrame) * alignedRowBytesY;
                    totalSegment.asSlice(offsetBytes, lengthBytes).fill(PADDING_BYTE);
                }
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
            }
        }
    }
}
