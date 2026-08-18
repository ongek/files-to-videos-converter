import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

public class FilesToVideosTransformerTask extends AbstractTransformerTask {

    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    private static final long M4_CACHE_LINE_ALIGNMENT = 128L; // Apple M4 L1D/L2 キャッシュライン (128 bytes)
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
        final int frameRate = inputCLIArgumentsHolder.getArgument(FRAMERATE);

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);

            try (Arena arena = Arena.ofConfined();
                 FileInputStream inputStream = new FileInputStream(processData);
                 FFmpegFrameRecorder videoRecorder = createConfiguredRecorder(resultVideoFile, imgWidth, imgHeight, frameRate)) {

                videoRecorder.start();

                // 128B ストライド対応のフレームライター初期化
                final AlignedPixelFrameWriter frameWriter = new AlignedPixelFrameWriter(
                        arena, videoRecorder, imgWidth, imgHeight, duplicateFactor,
                        bytesUtils.bitToPixel(0), bytesUtils.bitToPixel(1), taskStatistics
                );

                final byte[] readBuffer = new byte[IO_BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(readBuffer)) >= 0) {
                    frameWriter.writeBytes(readBuffer, bytesRead);
                }

                frameWriter.finish();
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

    private FFmpegFrameRecorder createConfiguredRecorder(File file, int width, int height, int frameRate) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file, width, height);
        recorder.setFormat("mp4");
        recorder.setFrameRate(frameRate);

        String activeCodec = (System.getenv("GITHUB_ACTIONS") != null) ? "libx265" : "hevc_videotoolbox";
        recorder.setVideoCodecName(activeCodec);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

        recorder.setVideoQuality(90);
        recorder.setOption("movflags", "faststart");
        recorder.setVideoOption("realtime", "1");
        recorder.setMaxBFrames(0);
        recorder.setOption("bf", "0");

        return recorder;
    }

    /**
     * Apple Silicon 128B キャッシュライン & imageStride パディング完全対応ライター
     */
    private static final class AlignedPixelFrameWriter {
        private final FFmpegFrameRecorder recorder;
        private final Frame reusableFrame;
        private final MemorySegment nativePixelSegment;
        private final TaskStatistics statistics;

        private final int imgHeight;
        private final int duplicateFactor;
        private final int rawRowPixels;         // 1行のピクセル数 (imgWidth)
        private final int tempRowLength;        // 拡大前の1行ピクセル数 (imgWidth / duplicateFactor)
        private final long alignedRowBytes;     // 128B アライメントされた1行のバイト数 (imageStride)
        private final long totalFrameBytes;     // パディングを含む1フレームの総バイト数

        private final int[] tempRow;
        private final int[] rowCache;
        private final int[][] bitToPixelLut;

        private int currentRowInFrame = 0;      // 現在フレーム内の行インデックス (0 .. imgHeight - 1)
        private int tempRowIndex = 0;

        public AlignedPixelFrameWriter(Arena arena, FFmpegFrameRecorder recorder, int width, int height,
                                       int duplicateFactor, int pixelZero, int pixelOne, TaskStatistics statistics) {
            this.recorder = recorder;
            this.statistics = statistics;
            this.imgHeight = height;
            this.duplicateFactor = duplicateFactor;
            this.rawRowPixels = width;
            this.tempRowLength = width / duplicateFactor;

            // --- 128バイト キャッシュライン・パディング計算 ---
            final int rawRowBytes = width * 4; // RGBA 4 bytes
            // 128の倍数に切り上げ: (bytes + 127) & ~127
            this.alignedRowBytes = (rawRowBytes + (M4_CACHE_LINE_ALIGNMENT - 1)) & ~(M4_CACHE_LINE_ALIGNMENT - 1);
            this.totalFrameBytes = this.alignedRowBytes * height;

            this.tempRow = new int[tempRowLength];
            this.rowCache = new int[width];

            // バッファ全体を 128B アライメントで確保
            this.nativePixelSegment = arena.allocate(this.totalFrameBytes, M4_CACHE_LINE_ALIGNMENT);
            final ByteBuffer byteBuffer = this.nativePixelSegment.asByteBuffer();

            // JavaCV Frame の初期化と imageStride の明示設定
            this.reusableFrame = new Frame(width, height, Frame.DEPTH_UBYTE, 4);
            this.reusableFrame.imageStride = (int) this.alignedRowBytes; // FFmpeg linesize に 128B ストライドを伝達
            this.reusableFrame.image[0] = byteBuffer;

            // 1byte -> 8pixels LUT
            this.bitToPixelLut = new int[256][8];
            for (int b = 0; b < 256; b++) {
                for (int bit = 0; bit < 8; bit++) {
                    this.bitToPixelLut[b][bit] = ((b & (1 << (7 - bit))) != 0) ? pixelOne : pixelZero;
                }
            }
        }

        public void writeBytes(byte[] buffer, int length) throws Exception {
            for (int i = 0; i < length; i++) {
                final int[] px8 = bitToPixelLut[buffer[i] & 0xFF];
                System.arraycopy(px8, 0, tempRow, tempRowIndex, 8);
                tempRowIndex += 8;

                if (tempRowIndex >= tempRowLength) {
                    flushRow();
                    tempRowIndex = 0;
                }
            }
        }

        public void finish() throws Exception {
            // 現在の行の残りをゼロ埋めしてフラッシュ
            if (tempRowIndex > 0) {
                Arrays.fill(tempRow, tempRowIndex, tempRowLength, 0);
                flushRow();
            }

            // フレーム内の残りの行をパディングしてフラッシュ
            if (currentRowInFrame > 0) {
                if (currentRowInFrame < imgHeight) {
                    long offsetBytes = currentRowInFrame * alignedRowBytes;
                    long remainingBytes = (imgHeight - currentRowInFrame) * alignedRowBytes;
                    nativePixelSegment.asSlice(offsetBytes, remainingBytes).fill(PADDING_BYTE);
                }
                recordFrame();
            }
        }

        private void flushRow() throws Exception {
            if (duplicateFactor == 1) {
                if (currentRowInFrame >= imgHeight) {
                    recordFrame();
                }

                // 行の先頭アドレス（必ず 128 バイトの倍数位置）へコピー
                long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
                MemorySegment.copy(tempRow, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, rawRowPixels);
                currentRowInFrame++;
                return;
            }

            // 横拡大
            int cacheIdx = 0;
            for (int i = 0; i < tempRowLength; i++) {
                final int px = tempRow[i];
                for (int f = 0; f < duplicateFactor; f++) {
                    rowCache[cacheIdx++] = px;
                }
            }

            // 縦拡大（各行が 128B ストライド境界に配置される）
            for (int r = 0; r < duplicateFactor; r++) {
                if (currentRowInFrame >= imgHeight) {
                    recordFrame();
                }

                long rowOffsetBytes = currentRowInFrame * alignedRowBytes;
                MemorySegment.copy(rowCache, 0, nativePixelSegment, ValueLayout.JAVA_INT, rowOffsetBytes, rawRowPixels);
                currentRowInFrame++;
            }
        }

        private void recordFrame() throws Exception {
            recorder.record(reusableFrame, avutil.AV_PIX_FMT_RGBA);
            statistics.poll();
            currentRowInFrame = 0;
        }
    }

    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        final int searchLimit = 2 * 1024 * 1024;
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
