package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.*;
import java.nio.ByteBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.VIDEOS_PATH;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    // === 【Snow Leopard 4.6】超高効率・ゼロアロケーション定数領域 ===
    private final byte[] bulkZeroBuffer = new byte[16384]; 

    public VideosToFilesTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        taskStatistics.setFilePath(processData.getAbsolutePath());

        File resultFile;
        try {
            String currentOriginalFile = fileUtils.getOriginalNameOfFile(processData, inputCLIArgumentsHolder.getArgument(VIDEOS_PATH));
            resultFile = fileUtils.getVideosToFilesResultFile(currentOriginalFile);
        } catch (Exception e) {
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile), IO_BUFFER_SIZE)) {
            int duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
            
            processFile(processData, outputStream, duplicateFactor);

            int lastZeroBytesCount = fileUtils.getImageLastZeroBytesCount(processData.getAbsolutePath());
            if (lastZeroBytesCount > 0) {
                writeZeroBytesWithCount(lastZeroBytesCount, outputStream);
            }
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    private void processFile(File video, OutputStream outputStream, int duplicateFactor) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto");
            
            // 配信サービスによる再エンコードコンテナ（H.264/VP9/AV1等）に動的追従
            grabber.start();

            final int width = grabber.getImageWidth();
            final int height = grabber.getImageHeight();

            // 【Snow Leopard 4.6】輝度レンジのオート調整
            // 再圧縮で潰れた黒・白のコントラストをネイティブフィルタ側で完全フルレンジへ引き伸ばし
            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("scale=in_range=auto:out_range=full,format=gray", width, height)) {
                filter.start();
                
                int currentBitsCount = 0;
                int currentByteVal = 0;
                long zeroBytesCount = 0;

                final int blockRows = height / duplicateFactor;
                final int blockCols = width / duplicateFactor;

                // 境界ノイズを避けるための中心座標サンプリング位置を前置計算
                final int halfFact = duplicateFactor >> 1;
                final int prevFact = (halfFact - 1 >= 0) ? halfFact - 1 : halfFact;
                
                // ループ内乗算を排除するためのステップ定数化
                final int rowStride = duplicateFactor * width;
                final int colStride = duplicateFactor;

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    if (frame.image == null) continue; 

                    filter.push(frame);
                    Frame filteredFrame = filter.pull();

                    if (filteredFrame == null || filteredFrame.image == null) continue;

                    // ネイティブポインタをダイレクトアタッチ
                    final ByteBuffer nativeBuffer = (ByteBuffer) filteredFrame.image[0];
                    
                    // --- 構造改変：再エンコードによる「閾値シフト」に追従する動的ディザリング境界値 ---
                    // 配信サービス経由だと白黒の絶対値が歪むため、フレームごとに中央ピクセルから期待値をサンプリングするか、
                    // H.265の特徴である輝度沈みを補正するため一律で「120」付近にバイアスを設定（黒をより広く拾う）
                    final int dynamicThreshold = 120; 

                    // --- パターン1: 1:1 ダイレクトサンプリング (duplicateFactor == 1) ---
                    if (duplicateFactor == 1) {
                        final int totalPixels = width * height;
                        final int unrolledLen = totalPixels & ~3; 
                        int i = 0;
                        
                        // Apple Silicon M1/M2/M3/M4の多段階実行ユニットをフルドライブさせる4ウェイアンロール
                        for (; i < unrolledLen; i += 4) {
                            final int b0 = ((dynamicThreshold - (nativeBuffer.get(i) & 0xFF)) >>> 31);
                            final int b1 = ((dynamicThreshold - (nativeBuffer.get(i + 1) & 0xFF)) >>> 31);
                            final int b2 = ((dynamicThreshold - (nativeBuffer.get(i + 2) & 0xFF)) >>> 31);
                            final int b3 = ((dynamicThreshold - (nativeBuffer.get(i + 3) & 0xFF)) >>> 31);

                            currentByteVal = (currentByteVal << 1) | b0;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0; currentBitsCount = 0;
                            }
                            currentByteVal = (currentByteVal << 1) | b1;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0; currentBitsCount = 0;
                            }
                            currentByteVal = (currentByteVal << 1) | b2;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0; currentBitsCount = 0;
                            }
                            currentByteVal = (currentByteVal << 1) | b3;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0; currentBitsCount = 0;
                            }
                        }
                        
                        for (; i < totalPixels; i++) {
                            final int bit = ((dynamicThreshold - (nativeBuffer.get(i) & 0xFF)) >>> 31);
                            currentByteVal = (currentByteVal << 1) | bit;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0; currentBitsCount = 0;
                            }
                        }
                    } 
                    // --- パターン2: 【超強化】マジョリティ・クロスサンプリング (duplicateFactor > 1) ---
                    else {
                        // ループ内でのインデックス計算による乗算を完全に排除
                        int y1RowOffset = prevFact * width; 
                        
                        for (int r = 0; r < blockRows; r++) {
                            final int y2RowOffset = y1RowOffset + width;

                            for (int c = 0; c < blockCols; c++) {
                                final int baseColOffset = c * colStride + prevFact;
                                
                                // ブロック中央4マスのメモリアドレスを連続ヒットさせ、L1キャッシュ内でロード
                                final int p00 = nativeBuffer.get(y1RowOffset + baseColOffset) & 0xFF;
                                final int p01 = nativeBuffer.get(y1RowOffset + baseColOffset + 1) & 0xFF;
                                final int p10 = nativeBuffer.get(y2RowOffset + baseColOffset) & 0xFF;
                                final int p11 = nativeBuffer.get(y2RowOffset + baseColOffset + 1) & 0xFF;

                                // ノンブランチ符号抽出（120より小さければビット1[黒]、大きければ0[白]）
                                final int v00 = ((dynamicThreshold - p00) >>> 31);
                                final int v01 = ((dynamicThreshold - p01) >>> 31);
                                final int v10 = ((dynamicThreshold - p10) >>> 31);
                                final int v11 = ((dynamicThreshold - p11) >>> 31);

                                // 4マス中、半分以上（2マス以上、もしくは安全を見て3マス）を判定するロジック
                                // YouTube等のAVC再圧縮はブロック境界に「モスキートノイズ」を発生させるため、
                                // 2マス以上（過半数以上）が黒であれば「1」と判定する耐ノイズ・マジョリティへ拡張
                                final int voteSum = v00 + v01 + v10 + v11;
                                final int bit = ((1 - voteSum) >>> 31); // voteSum >= 2 で 1、それ未満で 0

                                currentByteVal = (currentByteVal << 1) | bit;
                                if (++currentBitsCount == 8) {
                                    zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                    currentByteVal = 0;
                                    currentBitsCount = 0;
                                }
                            }
                            // 次のブロック行へストライドを加算（乗算を排除した超高速ポインタ移動）
                            y1RowOffset += rowStride;
                        }
                    }
                    taskStatistics.poll();
                }

                if (currentBitsCount > 0) {
                    int finalByte = currentByteVal << (8 - currentBitsCount);
                    zeroBytesCount = appendByteToStream(finalByte, zeroBytesCount, outputStream);
                }
                
                if (zeroBytesCount > 0) {
                    writeZeroBytesWithCount(zeroBytesCount, outputStream);
                }
            }
        }
    }

    private long appendByteToStream(int byteVal, long zeroBytesCount, OutputStream outputStream) throws IOException {
        if (byteVal == 0) {
            return zeroBytesCount + 1;
        }
        if (zeroBytesCount > 0) {
            writeZeroBytesWithCount(zeroBytesCount, outputStream);
        }
        outputStream.write(byteVal);
        return 0;
    }

    private long writeZeroBytesWithCount(long count, OutputStream outputStream) throws IOException {
        long remaining = count;
        final int bufferLength = bulkZeroBuffer.length;

        while (remaining > 0) {
            final int bytesToWrite = (int) Math.min(remaining, bufferLength);
            outputStream.write(bulkZeroBuffer, 0, bytesToWrite);
            remaining -= bytesToWrite;
        }
        return 0; 
    }
}
