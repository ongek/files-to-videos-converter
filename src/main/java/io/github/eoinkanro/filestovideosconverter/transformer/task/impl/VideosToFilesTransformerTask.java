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

    // === 【Snow Leopard 4.6】ゼロアロケーション用定数領域 ===
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

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(resultFile))) {
            duplicateFactor = fileUtils.getImageDuplicateFactor(processData.getAbsolutePath());
            
            processFile(processData, outputStream);

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
            grabber.setOption("threads", "auto");
            grabber.start();

            final int width = grabber.getImageWidth();
            final int height = grabber.getImageHeight();

            // 再圧縮（MPEGレンジ）をフルレンジに引き戻すネイティブフィルタ
            try (FFmpegFrameFilter filter = new FFmpegFrameFilter("scale=in_range=auto:out_range=full,format=gray", width, height)) {
                filter.start();
                
                int currentBitsCount = 0;
                int currentByteVal = 0;
                long zeroBytesCount = 0;

                // オリジナルの「ブロックの縦横数」の計算を厳密にトレース
                final int blockRows = height / duplicateFactor;
                final int blockCols = width / duplicateFactor;

                // 境界ノイズを避けるための中心座標
                final int halfFact = duplicateFactor >> 1;
                final int prevFact = (halfFact - 1 >= 0) ? halfFact - 1 : halfFact;

                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    if (frame.image == null) continue; 

                    filter.push(frame);
                    Frame filteredFrame = filter.pull();

                    if (filteredFrame == null || filteredFrame.image == null) continue;

                    // C++のネイティブヒープ上のバッファをダイレクトに参照（アロケーションゼロ）
                    final ByteBuffer nativeBuffer = (ByteBuffer) filteredFrame.image[0];
                    
                    // 実機CRF90環境および再圧縮環境に適合する輝度境界
                    final int dynamicThreshold = 120;

                    // 外側ループを「縦ブロック」→ 内側を「横ブロック」にするオリジナル通りの二次元走査
                    for (int r = 0; r < blockRows; r++) {
                        // サンプリング対象となるブロック中央の行インデックスを計算
                        final int targetY = r * duplicateFactor + prevFact;
                        final int rowOffset = targetY * width;

                        for (int c = 0; c < blockCols; c++) {
                            // サンプリング対象となるブロック中央の列インデックスを計算
                            final int targetX = c * duplicateFactor + prevFact;
                            
                            // ピクセル値をダイレクトロード
                            final int pixelVal = nativeBuffer.get(rowOffset + targetX) & 0xFF;

                            // 【重要】オリジナルに準拠した正確なビット抽出
                            // 輝度が閾値より小さければ「黒（1）」、大きければ「白（0）」
                            final int bit = (pixelVal < dynamicThreshold) ? 1 : 0;

                            // StringBuilderを排除し、プリミティブなビットシフトで直接バイトを組み立て
                            currentByteVal = (currentByteVal << 1) | bit;
                            if (++currentBitsCount == 8) {
                                zeroBytesCount = appendByteToStream(currentByteVal, zeroBytesCount, outputStream);
                                currentByteVal = 0;
                                currentBitsCount = 0;
                            }
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
