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

        try {
            File resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            // 【Java 26 FFM API】mmap とネイティブメモリを単一の Arena で完全ゼロコピー管理
            try (Arena arena = Arena.ofConfined();
                 RandomAccessFile raf = new RandomAccessFile(processData, "r");
                 FileChannel fileChannel = raf.getChannel();
                 FFmpegFrameRecorder videoRecorder = createConfiguredRecorder(resultVideoFile, imgWidth, imgHeight)) {

                final long fileSize = processData.length();
                final MemorySegment inputMappedSegment = (fileSize > 0)
                        ? fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena)
                        : MemorySegment.NULL;

                videoRecorder.start();

                // ダブルバッファリング対応ストリームライター
                final DoubleBufferedFrameWriter frameWriter = new DoubleBufferedFrameWriter(
                        arena, videoRecorder, taskStatistics, imgWidth, imgHeight, duplicateFactor, alignedRowBytes, totalFrameBytes
                );

                final int bytesPerBlockRow = imgWidth / (duplicateFactor << 3); // 1行に必要な入力バイト数 (df=4なら40B)
                long inputOffset = 0;

                // 行単位で一気に処理
                while (inputOffset + bytesPerBlockRow <= fileSize) {
                    frameWriter.writeFullRow(inputMappedSegment, inputOffset, bytesPerBlockRow);
                    inputOffset += bytesPerBlockRow;
                }

                // 端数バイトの処理
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

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private FFmpegFrameRecorder createConfiguredRecorder(File targetFile, int width, int height) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(targetFile, width, height);
        recorder.setFormat("mp4");
        recorder.setOption("movflags", "faststart");
        // 【最適化】FourCC を最初から hvc1 に指定 (エンコード後のファイルパッチ処理を完全撤廃)
        recorder.setOption("tag:v", "hvc1");
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

    /**
     * 【Double-Buffered Native Streamer】
     * 2つのネイティブフレームバッファを交互に回し、CPU生成とHWエンコードを完全非同期並行化
     */
    private static class DoubleBufferedFrameWriter {
        private final FFmpegFrameRecorder videoRecorder;
        private final Object taskStatistics;
        private final int imgWidth;
        private final int imgHeight;
        private final int duplicateFactor;
        private final long alignedRowBytes;
        private final long rowSizeBytes;

        // ダブルバッファ (Buffer 0 / Buffer 1)
        private final MemorySegment[] frameSegments = new MemorySegment[2];
        private final Frame[] frames = new Frame[2];
        private int activeBufferIdx = 0;

        private int currentRowInFrame = 0;
        private int currentPixelInRow = 0;

        public DoubleBufferedFrameWriter(Arena arena, FFmpegFrameRecorder videoRecorder,
                                         Object taskStatistics, int imgWidth, int imgHeight,
                                         int duplicateFactor, long alignedRowBytes, long totalFrameBytes) {
            this.videoRecorder = videoRecorder;
            this.taskStatistics = taskStatistics;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.duplicateFactor = duplicateFactor;
            this.alignedRowBytes = alignedRowBytes;
            this.rowSizeBytes = (long) imgWidth * 4;

            // 2つの独立した Native メモリセグメントを確保
            for (int i = 0; i < 2; i++) {
                this.frameSegments[i] = arena.allocate(totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
                this.frames[i] = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
                this.frames[i].imageStride = (int) alignedRowBytes;
                this.frames[i].image[0] = this.frameSegments[i].asByteBuffer();
            }
        }

        public void writeFullRow(MemorySegment inputSegment, long inputOffset, int bytesCount) throws Exception {
            MemorySegment currentSegment = frameSegments[activeBufferIdx];
            long writeOffset = (long) currentRowInFrame * alignedRowBytes;

            if (duplicateFactor == 4) {
                // 【M4 128B 一括ブロック転送】16回の個別 set ではなく、1回の copy で 128B を一撃転送
                for (int i = 0; i < bytesCount; i++) {
                    int val = inputSegment.get(ValueLayout.JAVA_BYTE, inputOffset + i) & 0xFF;
                    int lutBase = val << 4;

                    MemorySegment.copy(DF4_CACHE_LINE_LUT, lutBase, currentSegment, ValueLayout.JAVA_LONG, writeOffset, 16);
                    writeOffset += 128;
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
            MemorySegment currentSegment = frameSegments[activeBufferIdx];
            long rowBaseOffset = (long) currentRowInFrame * alignedRowBytes;
            long writeOffset = rowBaseOffset + ((long) currentPixelInRow << 2);
            int val = b & 0xFF;

            if (duplicateFactor == 4) {
                int lutBase = val << 4;
                MemorySegment.copy(DF4_CACHE_LINE_LUT, lutBase, currentSegment, ValueLayout.JAVA_LONG, writeOffset, 16);
                currentPixelInRow += 32;
            } else if (duplicateFactor == 1) {
                int lutOffset = val << 3;
                for (int p = 0; p < 8; p++) {
                    currentSegment.set(ValueLayout.JAVA_INT, writeOffset + (p << 2), BytesUtils.BIT_TO_PIXEL_FLAT_LUT[lutOffset + p]);
                }
                currentPixelInRow += 8;
            } else {
                for (int bit = 7; bit >= 0; bit--) {
                    int px = ((val & (1 << bit)) != 0) ? BytesUtils.ONE : BytesUtils.ZERO;
                    for (int f = 0; f < duplicateFactor; f++) {
                        currentSegment.set(ValueLayout.JAVA_INT, writeOffset, px);
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
            MemorySegment currentSegment = frameSegments[activeBufferIdx];
            long firstRowOffset = (long) currentRowInFrame * alignedRowBytes;
            currentRowInFrame++;

            // 【スライス生成ゼロ】Native-to-Native ダイレクトコピー
            if (duplicateFactor > 1) {
                for (int r = 1; r < duplicateFactor; r++) {
                    long nextRowOffset = (long) currentRowInFrame * alignedRowBytes;
                    MemorySegment.copy(currentSegment, ValueLayout.JAVA_BYTE, firstRowOffset,
                                       currentSegment, ValueLayout.JAVA_BYTE, nextRowOffset, rowSizeBytes);
                    currentRowInFrame++;
                }
            }

            // フレームが満杯になったらエンコーダへ送り、バッファを切り替える (ダブルバッファリング)
            if (currentRowInFrame >= imgHeight) {
                videoRecorder.record(frames[activeBufferIdx], AV_PIX_FMT_RGBA);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
                
                // 次のフレームへ (0 <-> 1 の切り替え)
                activeBufferIdx = 1 - activeBufferIdx;
                currentRowInFrame = 0;
            }
        }

        public void finish() throws Exception {
            MemorySegment currentSegment = frameSegments[activeBufferIdx];

            if (currentPixelInRow > 0) {
                long writeOffset = (long) currentRowInFrame * alignedRowBytes + ((long) currentPixelInRow << 2);
                long remainingBytes = rowSizeBytes - ((long) currentPixelInRow << 2);
                currentSegment.asSlice(writeOffset, remainingBytes).fill((byte) 0xFF);
                currentPixelInRow = 0;
                commitRow();
            }

            if (currentRowInFrame > 0) {
                if (currentRowInFrame < imgHeight) {
                    long offsetBytes = (long) currentRowInFrame * alignedRowBytes;
                    long lengthBytes = (imgHeight - currentRowInFrame) * alignedRowBytes;
                    currentSegment.asSlice(offsetBytes, lengthBytes).fill(PADDING_BYTE);
                }
                videoRecorder.record(frames[activeBufferIdx], AV_PIX_FMT_RGBA);
                if (taskStatistics instanceof io.github.eoinkanro.filestovideosconverter.transformer.task.TaskStatistics stats) {
                    stats.poll();
                }
            }
        }
    }
}
