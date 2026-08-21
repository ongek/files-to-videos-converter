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
    // RGBA (128B) から Y (32B) に激減し、sws_scale を完全抹消
    private static final long[] DF4_Y_PLANE_LUT = new long[256 * 4];

    static {
        // 白: Y=255 (0xFFFFFFFFFFFFFFFFL), 黒: Y=0 (0x0000000000000000L)
        long blackQuad = 0x00000000_00000000L; // 黒4画素 (4 bytes = 32bit 0) -> 2つで 64bit
        long whiteQuad = 0xFFFFFFFF_FFFFFFFFL; // 白4画素 (4 bytes = 32bit FF)

        for (int b = 0; b < 256; b++) {
            int base = b << 2; // * 4
            // 8 ビットを 32 バイト (4 long) に展開 (1ビット = 横4バイト)
            for (int pair = 0; pair < 4; pair++) {
                int bit0 = (b >> (7 - (pair * 2))) & 1;
                int bit1 = (b >> (7 - (pair * 2 + 1))) & 1;

                long val0 = (bit0 != 0) ? blackQuad : whiteQuad; // 4 bytes (下位32bit)
                long val1 = (bit1 != 0) ? blackQuad : whiteQuad; // 4 bytes (上位32bit)
                // Little-Endian で 8 bytes (8画素)
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

        // YUV420P の各プレーンサイズ計算 (128B アライメント)
        final long alignedRowBytesY = (imgWidth + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long yPlaneBytes = alignedRowBytesY * imgHeight;
        
        final int uvWidth = imgWidth / 2;
        final int uvHeight = imgHeight / 2;
        final long alignedRowBytesUV = (uvWidth + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
        final long uvPlaneBytes = alignedRowBytesUV * uvHeight;

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

                // YUV420P ダイレクトライター
                final DirectYUV420PFrameWriter frameWriter = new DirectYUV420PFrameWriter(
                        arena, videoRecorder, taskStatistics, imgWidth, imgHeight, duplicateFactor,
                        alignedRowBytesY, yPlaneBytes, alignedRowBytesUV, uvPlaneBytes
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
     * 【Direct YUV420P Writer (sws_scale 完全バイパス)】
     */
    private static class DirectYUV420PFrameWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final Object taskStatistics;
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytesY;
        private final long rowSizeBytesY;

        private final MemorySegment ySegment;
        private final Frame reusableFrame;

        private int currentRowInFrame = 0;
        private int currentPixelInRow = 0;

        public DirectYUV420PFrameWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                        Object taskStatistics, int imgWidth, int imgHeight,
                                        int duplicateFactor, long alignedRowBytesY, long yPlaneBytes,
                                        long alignedRowBytesUV, long uvPlaneBytes) {
            this.videoRecorder = videoRecorder;
            this.taskStatistics = taskStatistics;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytesY = alignedRowBytesY;
            this.rowSizeBytesY = imgWidth; // Y プレーンは 1画素 1バイト

            // 1. Y プレーンの確保
            this.ySegment = arena.allocate(yPlaneBytes, M4_CACHE_LINE_ALIGNMENT);

            // 2. U プレーン / V プレーンの確保 (白黒なので 128 (0x80) で一度だけ完全初期化)
            MemorySegment uSegment = arena.allocate(uvPlaneBytes, M4_CACHE_LINE_ALIGNMENT);
            MemorySegment vSegment = arena.allocate(uvPlaneBytes, M4_CACHE_LINE_ALIGNMENT);
            uSegment.fill((byte) 128);
            vSegment.fill((byte) 128);

            // 3. YUV420P Frame の構築 (3プレーン直結)
            this.reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 3);
            this.reusableFrame.imageStride = (int) alignedRowBytesY;
            this.reusableFrame.image = new ByteBuffer[] {
                    this.ySegment.asByteBuffer(),
                    uSegment.asByteBuffer(),
                    vSegment.asByteBuffer()
            };
        }

        public void writeFullRow(MemorySegment inputSegment, long inputOffset, int bytesCount) throws Exception {
            long writeOffset = (long) currentRowInFrame * alignedRowBytesY;

            if (duplicateFactor == 4) {
                // df=4: 1バイト -> 32バイト (4 long) を Y プレーンへ直書き
                for (int i = 0; i < bytesCount; i++) {
                    int val = inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i) & 0xFF;
                    int lutBase = val << 2; // * 4

                    ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset,      DF4_Y_PLANE_LUT[lutBase]);
                    ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 8,  DF4_Y_PLANE_LUT[lutBase + 1]);
                    ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 16, DF4_Y_PLANE_LUT[lutBase + 2]);
                    ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 24, DF4_Y_PLANE_LUT[lutBase + 3]);

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
                ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset,      DF4_Y_PLANE_LUT[lutBase]);
                ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 8,  DF4_Y_PLANE_LUT[lutBase + 1]);
                ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 16, DF4_Y_PLANE_LUT[lutBase + 2]);
                ySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset + 24, DF4_Y_PLANE_LUT[lutBase + 3]);
                currentPixelInRow += 32;
            } else {
                for (int bit = 7; bit >= 0; bit--) {
                    byte yVal = ((val & (1 << bit)) != 0) ? (byte) 0 : (byte) 255;
                    for (int f = 0; f < duplicateFactor; f++) {
                        ySegment.set(ValueLayout.JAVA_BYTE, writeOffset++, yVal);
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

            // 縦方向拡大 (Yプレーン内の Native 高速複製)
            if (duplicateFactor > 1) {
                MemorySegment firstRowSlice = ySegment.asSlice(firstRowOffset, rowSizeBytesY);
                for (int r = 1; r < duplicateFactor; r++) {
                    long nextRowOffset = (long) currentRowInFrame * alignedRowBytesY;
                    ySegment.asSlice(nextRowOffset, rowSizeBytesY).copyFrom(firstRowSlice);
                    currentRowInFrame++;
                }
            }

            // 【超重要】AV_PIX_FMT_YUV420P で録画 -> sws_scale を 100% バイパス！
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
                ySegment.asSlice(writeOffset, remainingBytes).fill(PADDING_BYTE);
                currentPixelInRow = 0;
                commitRow();
            }

            if (currentRowInFrame > 0) {
                if (currentRowInFrame < imgHeight) {
                    long offsetBytes = (long) currentRowInFrame * alignedRowBytesY;
                    long lengthBytes = (imgHeight - currentRowInFrame) * alignedRowBytesY;
                    ySegment.asSlice(offsetBytes, lengthBytes).fill(PADDING_BYTE);
                }
                videoRecorder.record(reusableFrame, AV_PIX_FMT_YUV420P);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
            }
        }
    }
}
