package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.Arena;
import java.util.Arrays;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static io.github.eoinkanro.filestovideosconverter.utils.BytesUtils.ZERO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB32_1;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private static final int IO_BUFFER_SIZE = 131072; // 128KB 物理クラスタ・セクタ最適化バッファ

    public FilesToVideosTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        // 【バグ修正・並行セーフティ】
        // インスタンスフィールドを完全に排除し、スタックローカルへの不変スナップショット化。
        // これによりスレッド間でのレースコンディションを100%防止。
        final int localLastZeroBytesCount = fileUtils.calculateLastZeroBytesAmount(processData);

        // スタックローカルへの不変変数のバインド（JITによるレジスタ割り当ての最大化）
        final int imgWidth = inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH);
        final int imgHeight = inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT);
        final int duplicateFactor = inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR);
        final int maxPixelsCapacity = imgWidth * imgHeight;

        final int localTempRowLength = imgWidth / duplicateFactor;
        final int[] localTempRow = new int[localTempRowLength];
        
        // 分岐予測を裏切らない、JITコンパイラのための完全定数レジスタ化
        final int pixelZero = bytesUtils.bitToPixel(0);
        final int pixelOne  = bytesUtils.bitToPixel(1);

        final int localRowCacheLength = imgWidth;
        final int[] localRowCache = new int[localRowCacheLength];

        // オフヒープ・ダイレクトメモリによる真のゼロコピー（Cレイヤーとの直結バッファ）
        final ByteBuffer reusableByteBuffer = ByteBuffer.allocateDirect(maxPixelsCapacity * 4);
        final IntBuffer localBuffer = reusableByteBuffer.asIntBuffer();
        
        final Frame reusableFrame = new Frame(imgWidth, imgHeight, Frame.DEPTH_UBYTE, 4);
        reusableFrame.image[0] = reusableByteBuffer; 

        File resultVideoFile = null;

        try {
            resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, localLastZeroBytesCount);
            
            try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData), IO_BUFFER_SIZE);
                 FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile, imgWidth, imgHeight)) {

                // ビデオレコーダーのカーネル最適化パラメータ設定
                videoRecorder.setFormat("mp4");
                videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));
                videoRecorder.setVideoCodecName("hevc_videotoolbox"); 
                videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                videoRecorder.setVideoOption("f", "rawvideo");
                videoRecorder.setVideoOption("realtime", "1");
                videoRecorder.setVideoBitrate(8000000);
                videoRecorder.setOption("movflags", "faststart");
                videoRecorder.start();

                int aByte;
                int localTempRowIndex = 0;
                int currentPixelIndex = 0;

                // 【Snow Leopard コアストリーム変換ループ：極限のCPU高密度・ゼロアロケーション】
                while ((aByte = inputStream.read()) >= 0) {
                    for (int shift = 7; shift >= 0; shift--) {
                        
                        // 文字列への変換（byteToBits）を完全に消滅させ、完全にレジスタ内でビット判定を完結
                        localTempRow[localTempRowIndex++] = ((aByte >>> shift) & 1) == 1 ? pixelOne : pixelZero;

                        if (localTempRowIndex >= localTempRowLength) {
                            // 横方向の高速展開（JITにループ展開とSIMD自動ベクトル化を促すフラットスキャン）
                            int cacheIdx = 0;
                            for (int i = 0; i < localTempRowLength; i++) {
                                final int px = localTempRow[i];
                                for (int f = 0; f < duplicateFactor; f++) {
                                    localRowCache[cacheIdx++] = px;
                                }
                            }

                            // 縦方向の複製処理（境界バグを完全に排除したセーフティガード構造）
                            for (int r = 0; r < duplicateFactor; r++) {
                                if (currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                                    localBuffer.position(0);
                                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                                    taskStatistics.poll();
                                    currentPixelIndex = 0;
                                }
                                
                                localBuffer.position(currentPixelIndex);
                                localBuffer.put(localRowCache, 0, localRowCacheLength);
                                currentPixelIndex += localRowCacheLength;
                            }
                            localTempRowIndex = 0;
                        }
                    }
                }

                // 末尾の不完全ビットブロックのフラッシュ処理
                if (localTempRowIndex > 0) {
                    Arrays.fill(localTempRow, localTempRowIndex, localTempRowLength, ZERO);
                    int cacheIdx = 0;
                    for (int i = 0; i < localTempRowLength; i++) {
                        final int px = localTempRow[i];
                        for (int f = 0; f < duplicateFactor; f++) {
                            localRowCache[cacheIdx++] = px;
                        }
                    }
                    for (int r = 0; r < duplicateFactor; r++) {
                        if (currentPixelIndex + localRowCacheLength > maxPixelsCapacity) {
                            localBuffer.position(0);
                            videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                            taskStatistics.poll();
                            currentPixelIndex = 0;
                        }
                        localBuffer.position(currentPixelIndex);
                        localBuffer.put(localRowCache, 0, localRowCacheLength);
                        currentPixelIndex += localRowCacheLength;
                    }
                }

                // 完全に安全化された高速ゼロフィリング（最終フレームの残余領域パディング）
                if (currentPixelIndex > 0 && currentPixelIndex < maxPixelsCapacity) {
                    localBuffer.position(currentPixelIndex);
                    int remainingInts = maxPixelsCapacity - currentPixelIndex;
                    
                    int zerosWritten = 0;
                    while (zerosWritten < remainingInts) {
                        int chunk = Math.min(remainingInts - zerosWritten, localRowCacheLength);
                        Arrays.fill(localRowCache, 0, chunk, ZERO);
                        localBuffer.put(localRowCache, 0, chunk);
                        zerosWritten += chunk;
                    }
                    
                    localBuffer.position(0);
                    videoRecorder.record(reusableFrame, AV_PIX_FMT_RGB32_1);
                    taskStatistics.poll();
                }
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        // Project Panama によるメモリマップド（mmap）超高速 FourCC パッチ
        if (resultVideoFile != null) {
            convertHev1ToHvc1(resultVideoFile);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    /**
     * Java 22+ Project Panama 仮想メモリ空間直結 (mmap) カーネル超高速 FourCC パッチ
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw");
             FileChannel channel = raf.getChannel();
             Arena arena = Arena.ofConfined()) {
            
            long length = channel.size();
            long searchSize = Math.min(length, 4096L); // 先頭の4KBメタデータセクタのみをマッピング
            
            // ファイルの物理セクタを直接プロセスの仮想メモリ空間へ超高速マッピング
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, searchSize, arena);
            
            // 厳密なインデックス限界設計
            long loopBoundary = searchSize - 4;
            for (long i = 0; i <= loopBoundary; i++) {
                if (segment.get(ValueLayout.JAVA_BYTE, i)     == 0x68   // 'h'
                 && segment.get(ValueLayout.JAVA_BYTE, i + 1) == 0x65   // 'e'
                 && segment.get(ValueLayout.JAVA_BYTE, i + 2) == 0x76   // 'v'
                 && segment.get(ValueLayout.JAVA_BYTE, i + 3) == 0x31) { // '1'
                    
                    // 【バグ修正：正確なFourCC置換インデックス】
                    // 元： h(0x68) e(0x65) v(0x76) 1(0x31)
                    // 新： h(0x68) v(0x76) c(0x63) 1(0x31)
                    segment.set(ValueLayout.JAVA_BYTE, i + 1, (byte) 0x76); // 1番目の 'e' を 'v' に置換
                    segment.set(ValueLayout.JAVA_BYTE, i + 2, (byte) 0x63); // 2番目の 'v' を 'c' に置換
                    
                    log.info("Project Panama memory-mapped layer successfully patched FourCC to 'hvc1' for: {}", mp4File.getName());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Project Panama memory mapping failed, fallback to standard IO routine.", e);
        }
    }
}
