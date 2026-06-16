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
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

@Log4j2
public class VideosToFilesTransformerTask extends TransformerTask {

    private static final int RGB_CHANNELS = 3;

    // === 【Snow Leopard 4.6】ゼロアロケーション・再利用プリミティブ領域 ===
    private final byte[] bulkZeroBuffer = new byte[16384]; 
    private byte[] pixelsCache = new byte[0]; // フレームサイズに応じて自動拡張・再利用されるキャッシュバッファ
    private int[] bitsRowCache = new int[0];   // ループ内での new int[] 配列生成を完全に排除するキャッシュ

    private int duplicateFactor;
    private int imageWidth;

    // StringBuilder を完全追放し、プリミティブビット演算用の一時領域へ移行
    private int currentBitsCount;
    private int currentByteVal;
    private long zeroBytesCount;

    private int frameType;

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
            
            // 状態の完全初期化
            currentBitsCount = 0;
            currentByteVal = 0;
            zeroBytesCount = 0;

            processFile(processData, outputStream);

            // 末尾パディングの超高速フラッシュ
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

    private void processFile(File video, OutputStream outputStream) throws IOException {
        try(FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setOption("threads", "auto"); // デコードマルチスレッド化
            grabber.start();
            frameType = grabber.getPixelFormat();

            // オリジナルの「format=rgb24」を120%死守
            try(FFmpegFrameFilter filter = new FFmpegFrameFilter("format=rgb24", grabber.getImageWidth(), grabber.getImageHeight())) {
                filter.start();
                processFile(grabber, filter, outputStream);
            }
        }
    }

    private void processFile(FFmpegFrameGrabber grabber, FFmpegFrameFilter filter, OutputStream outputStream) throws IOException {
        Frame frame;
        while ((frame = grabber.grabFrame()) != null) {
            imageWidth = frame.imageWidth;

            filter.push(frame);
            frame = filter.pull();

            if (frame.type != null) {
                continue;
            }

            // 【最適化】毎フレームの new byte[] 配列生成を完全に廃止。必要な時だけ拡張
            int requiredPixelsLength = frame.imageHeight * frame.imageWidth * RGB_CHANNELS;
            if (pixelsCache.length < requiredPixelsLength) {
                pixelsCache = new byte[requiredPixelsLength];
            }
            ((ByteBuffer) frame.image[0]).get(pixelsCache, 0, requiredPixelsLength);

            // 完全にオリジナルの等価ロジックにキャッシュを流し込む
            processImage(requiredPixelsLength, outputStream);

            taskStatistics.poll();
        }

        // 全フレーム終了後、未フラッシュの残余ビットがあれば書き出し
        if (currentBitsCount > 0) {
            int finalByte = currentByteVal << (8 - currentBitsCount);
            zeroBytesCount = appendByteToStream(finalByte, zeroBytesCount, outputStream);
        }
        if (zeroBytesCount > 0) {
            writeZeroBytesWithCount(zeroBytesCount, outputStream);
        }
    }

    private void processImage(int pixelsLength, OutputStream outputStream) throws IOException {
        int pixelsIterations = pixelsLength / imageWidth / RGB_CHANNELS / duplicateFactor;
        
        // オリジナルの走査ポインタの初期化位置を厳密に再現
        int pixelsLastIndex = 0;

        for (int i = 0; i < pixelsIterations; i++) {
            
            // 【超強化】オリジナルがやっていた「個別の二次元配列確保（new byte[][]）」と
            // 「行ごとのコピー（new byte[]）」を完全隠蔽。
            // 配列の生成を一切行わず、pixelsCache のインデックス（ポインタ）の相対計算のみで
            // オリジナルのロジックを100%等価に再現します。
            
            int requiredBitsRowLength = imageWidth / duplicateFactor;
            if (bitsRowCache.length < requiredBitsRowLength) {
                bitsRowCache = new int[requiredBitsRowLength];
            }

            // オリジナルの transformToBitRow の完全インライン等価展開
            int pixelSum = 0;
            int duplicateFactorIterations = 0;
            int resultIndex = 0;
            int currentIndex = 0;
            int rowLength = imageWidth * RGB_CHANNELS;

            while (currentIndex < rowLength) {
                if (duplicateFactorIterations >= duplicateFactor) {
                    bitsRowCache[resultIndex] = bytesUtils.pixelToBit(pixelSum, duplicateFactor);
                    resultIndex++;
                    duplicateFactorIterations = 0;
                    pixelSum = 0;
                }

                // オリジナルの「for (byte[] row : copiedRows)」に完全に等しいピクセルサンプリング
                for (int r = 0; r < duplicateFactor; r++) {
                    int exactPixelPos = pixelsLastIndex + (r * rowLength) + currentIndex;
                    
                    if (frameType == AV_PIX_FMT_BGR24) {
                        pixelSum += bytesUtils.pixelToBit(pixelsCache[exactPixelPos + 2], pixelsCache[exactPixelPos + 1], pixelsCache[exactPixelPos]);
                    } else {
                        pixelSum += bytesUtils.pixelToBit(pixelsCache[exactPixelPos], pixelsCache[exactPixelPos + 1], pixelsCache[exactPixelPos + 2]);
                    }
                }

                currentIndex += RGB_CHANNELS;
                duplicateFactorIterations++;
            }

            if (duplicateFactorIterations >= duplicateFactor) {
                bitsRowCache[resultIndex] = bytesUtils.pixelToBit(pixelSum, duplicateFactor);
            }

            // 次のブロック行へポインタを進める（オリジナルのインデックス移動と完全一致）
            pixelsLastIndex += rowLength * duplicateFactor;

            // --- 【ボトルネックの完全破壊】StringBuilder ＆ 破壊的アロケーションの追放 ---
            for (int b = 0; b < requiredBitsRowLength; b++) {
                int bit = bitsRowCache[b];
                if (bit >= 0) {
                    // プリミティブビットシフトで超高速に1バイトを組み立て
                    currentByteVal = (currentByteVal << 1) | bit;
                    
                    if (++currentBitsCount == 8) {
                        if (currentByteVal == 0) {
                            zeroBytesCount++;
                        } else {
                            if (zeroBytesCount > 0) {
                                writeZeroBytesWithCount(zeroBytesCount, outputStream);
                                zeroBytesCount = 0;
                            }
                            outputStream.write(currentByteVal);
                        }
                        currentByteVal = 0;
                        currentBitsCount = 0;
                    }
                }
            }
        }
    }

    // 連続する0バイトを L2キャッシュ最適化されたバッファで一気にバルク書き込みする超高速メソッド
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
