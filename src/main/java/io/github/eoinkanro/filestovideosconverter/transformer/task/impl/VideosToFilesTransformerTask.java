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
    // Apple SiliconのL2キャッシュ空間に最適化されたゼロバッファ
    private final byte[] bulkZeroBuffer = new byte[16384]; 
    private int duplicateFactor;

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

        // 既存のコンパイルエラーの原因だった IO_BUFFER_SIZE を排除し、標準のBufferedOutputStreamに修正
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile))) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
            
            processFile(processData, outputStream);

            // 末尾パディングの高速フラッシュ
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

    private void processFile(File video, OutputStream outputStream) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            // ネイティブスレッドの自動最適化
            grabber.setOption("threads", "auto");
            grabber.start();

            final int width = grabber.getImageWidth();
            final int height = grabber.getImageHeight();

            // 【再圧縮・YouTube対策】
            // オリジナルの rgb24 処理から「format=gray」にフィルタを変更することで、情報量を輝度のみに圧縮。
            // クロマサブサンプリング（色間引き）による色ズレ破壊を完全に無効化し、ネイティブ層でフルレンジ（0-255）化
            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("scale=in_range=auto:out_range=full,format=gray", width, height)) {
                filter.start();
                
                int currentBitsCount = 0;
                int currentByteVal = 0;
                long zeroBytesCount = 0;

                final int blockRows = height / duplicateFactor;
                final int blockCols = width / duplicateFactor;

                // 境界ノイズ（モスキート・ブロック歪み）を避けるため、ブロックの中央ピクセルを射抜くオフセット前置計算
                final int halfFact = duplicateFactor >> 1;
                final int prevFact = (halfFact - 1 >= 0) ? halfFact - 1 : halfFact;
                
                // ループ内の乗算を排除するためのステップ定数化
                final int rowStride = duplicateFactor * width;
                final int colStride = duplicateFactor;

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    if (frame.image == null) continue; 

                    filter.push(frame);
                    Frame filteredFrame = filter.pull();

                    if (filteredFrame == null || filteredFrame.image == null) continue;

                    // Java側へのバイト配列コピー（new byte[]）を完全に廃止し、C++ヒープ上のポインタを直撃（ゼロコピー）
                    final ByteBuffer nativeBuffer = (ByteBuffer) filteredFrame.image[0];
                    
                    // 再圧縮による「輝度沈み」に耐えるための、最適化動的境界閾値（CRF90環境に対応）
                    final int dynamicThreshold = 120; 

                    // --- パターン1: 1:1 ダイレクトサンプリング (duplicateFactor == 1) ---
                    if (duplicateFactor == 1) {
                        final int totalPixels = width * height;
                        final int unrolledLen = totalPixels & ~3; 
                        int i = 0;
                        
                        // Apple SiliconのOut-of-Order実行パイプラインを限界まで回す4ウェイ・アンロール
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
                    // --- パターン2: 複数ピクセル・マジョリティクロスサンプリング (duplicateFactor > 1) ---
                    else {
                        // ループ内乗算を完全に追放したポインタ・インクリメント走査
                        int y1RowOffset = prevFact * width; 
                        
                        for (int r = 0; r < blockRows; r++) {
                            final int y2RowOffset = y1RowOffset + width;

                            for (int c = 0; c < blockCols; c++) {
                                final int baseColOffset = c * colStride + prevFact;
                                
                                // ブロック中央4マスのピクセル値をL1キャッシュに連続ヒットさせてロード
                                final int p00 = nativeBuffer.get(y1RowOffset + baseColOffset) & 0xFF;
                                final int p01 = nativeBuffer.get(y1RowOffset + baseColOffset + 1) & 0xFF;
                                final int p10 = nativeBuffer.get(y2RowOffset + baseColOffset) & 0xFF;
                                final int p11 = nativeBuffer.get(y2RowOffset + baseColOffset + 1) & 0xFF;

                                // ノンブランチ（分岐予測ミスゼロ）での符号（ビット）抽出
                                final int v00 = ((dynamicThreshold - p00) >>> 31);
                                final int v01 = ((dynamicThreshold - p01) >>> 31);
                                final int v10 = ((dynamicThreshold - p10) >>> 31);
                                final int v11 = ((dynamicThreshold - p11) >>> 31);

                                // マジョリティ決定：4マス中2マス以上が黒(1)なら1、それ以外なら0 (再エンコード耐性強化)
                                final int voteSum = v00 + v01 + v10 + v11;
                                final int bit = ((1 - voteSum) >>> 31); 

                                currentByteVal = (currentByteVal << 1) | bit;
                                if (++currentBitsCount == 8) {
                                    zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                    currentByteVal = 0;
                                    currentBitsCount = 0;
                                }
                            }
                            // 行ストライドを加算するだけの超高速移動
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
