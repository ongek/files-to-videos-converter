package io.github.eoinkanro.filestovideosconverter.transformer.task.impl;

import io.github.eoinkanro.filestovideosconverter.transformer.TransformException;
import io.github.eoinkanro.filestovideosconverter.transformer.task.TransformerTask;
import lombok.extern.log4j.Log4j2;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static io.github.eoinkanro.filestovideosconverter.conf.InputCLIArguments.*;
import static io.github.eoinkanro.filestovideosconverter.utils.BytesUtils.ZERO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB32_1;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

@Log4j2
public class FilesToVideosTransformerTask extends TransformerTask {

    private int lastZeroBytesCount;

    private int[] pixels;
    private int pixelIndex;

    private int[] tempRow;
    private int tempRowIndex;

    public FilesToVideosTransformerTask(File processData) {
        super(processData);
    }

    @Override
    protected void process() {
        log.info("Processing {}...", processData);
        init(processData);

        // あとで呼び出すために、生成される動画ファイルの参照をtryの外側に定義しておきます
        File resultVideoFile = fileUtils.getFilesToVideosResultFile(processData, lastZeroBytesCount);

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData));
             FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(resultVideoFile,
                                                                         inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH),
                                                                         inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT))) {

            initVideoRecorder(videoRecorder);
            int aByte;

            while ((aByte = inputStream.read()) >= 0) {
                String bits = bytesUtils.byteToBits(aByte);

                for (int i = 0; i < bits.length(); i++) {
                    if (tempRowIndex >= tempRow.length) {
                        writeTempRowIntoImage();
                        initTempRow();
                    }

                    if (pixelIndex >= pixels.length) {
                        writeImageIntoVideo(videoRecorder);
                        initPixels();
                    }

                    int pixel = bytesUtils.bitToPixel(Character.getNumericValue(bits.charAt(i)));
                    tempRow[tempRowIndex] = pixel;
                    tempRowIndex++;
                }
            }

            processLastPixels(videoRecorder);
            // ─── ここで try (FFmpegFrameRecorder) が閉じ、動画ファイルが物理的に保存されます ───
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

        // =================================================================
        // 【ここに挿入】動画が正常に書き閉じられた直後に、FourCCをバイナリレベルで偽装
        // =================================================================
        convertHev1ToHvc1(resultVideoFile);
        // =================================================================

        taskStatistics.logResult();
        log.info("File {} was processed successfully", processData);
    }

    /**
     * Prepare main information and objects
     *
     * @param file - file to convert
     */
    private void init(File file) {
        lastZeroBytesCount = fileUtils.calculateLastZeroBytesAmount(file);
        initPixels();
        initTempRow();

        taskStatistics.setFilePath(file.getAbsolutePath());
    }

    /**
     * Init new array with pixels of result image and index of processed pixel
     */
    private void initPixels() {
        pixels = new int[inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH) * inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT)];
        pixelIndex = 0;
    }

    /**
     * Init temp row for pixels that contains pixels without duplicate factor
     */
    private void initTempRow() {
        tempRow = new int[inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH) / inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR)];
        tempRowIndex = 0;
    }

    /**
     * Init ffmpeg recorder that creates result video with VideoToolbox H265 (hvc1 compatible)
     *
     * @param videoRecorder - ffmpeg recorder
     * @throws FFmpegFrameRecorder.Exception - if recorder can't be started
     */
    private void initVideoRecorder(FFmpegFrameRecorder videoRecorder) throws FFmpegFrameRecorder.Exception {
        videoRecorder.setFormat("mp4");
        videoRecorder.setFrameRate(inputCLIArgumentsHolder.getArgument(FRAMERATE));
        
        videoRecorder.setVideoCodecName("hevc_videotoolbox"); 
        videoRecorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);

        videoRecorder.setVideoOption("f", "rawvideo");
        videoRecorder.setVideoOption("realtime", "1");
        videoRecorder.setVideoBitrate(8000000);

        videoRecorder.setAudioChannels(0);
        videoRecorder.setSampleRate(0);

        videoRecorder.setOption("movflags", "faststart");

        videoRecorder.start();
    }

    /**
     * 【新規追加メソッド】生成されたMP4を直接バイナリハックするロジック
     * @param mp4File 生成された動画ファイル
     */
    private void convertHev1ToHvc1(File mp4File) {
        if (mp4File == null || !mp4File.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4File, "rw")) {
            long length = raf.length();
            byte[] buffer = new byte[4];
            
            // MP4のメタデータが含まれる先頭4KBのセクターだけを高速スキャン
            long maxSearchBytes = Math.min(length - 4, 4096); 
            
            for (long i = 0; i < maxSearchBytes; i++) {
                raf.seek(i);
                raf.readFully(buffer);
                
                // 「hev1」（0x68, 0x65, 0x76, 0x31）を発見した場合
                if (buffer[0] == 0x68 && buffer[1] == 0x65 && buffer[2] == 0x76 && buffer[3] == 0x31) {
                    // Apple互換の「hvc1」（0x68, 0x76, 0x63, 0x31）に直接ピンポイント上書き
                    raf.seek(i);
                    raf.write(new byte[]{0x68, 0x76, 0x63, 0x31});
                    
                    log.info("Successfully patched MP4 container FourCC from 'hev1' to 'hvc1' for: {}", mp4File.getName());
                    break; // 置換が完了したら即座にループを抜けて終了
                }
            }
        } catch (Exception e) {
            log.warn("Failed to patch MP4 FourCC metadata directly.", e);
        }
    }

    /**
     * Write temp row into several rows of result image using duplicate factor
     */
    private void writeTempRowIntoImage() {
        for (int i = 0; i < inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR); i++) {
            writeTempRowIntoOneRowOfImage();
        }
    }

    /**
     * Write temp row into one row of result image using duplicate factor
     */
    private void writeTempRowIntoOneRowOfImage() {
        for (int pixel : tempRow) {
            for (int i = 0; i < inputCLIArgumentsHolder.getArgument(DUPLICATE_FACTOR); i++) {
                pixels[pixelIndex] = pixel;
                pixelIndex++;
            }
        }
    }

    /**
     * Write frame to video
     */
    private void writeImageIntoVideo(FFmpegFrameRecorder videoRecorder) throws FFmpegFrameRecorder.Exception {
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length * 4);
        IntBuffer intBuffer = buffer.asIntBuffer();
        intBuffer.put(pixels);

        Frame frame = new Frame(inputCLIArgumentsHolder.getArgument(IMAGE_WIDTH), inputCLIArgumentsHolder.getArgument(IMAGE_HEIGHT), Frame.DEPTH_UBYTE, 4);
        frame.image[0].position(0);
        ((ByteBuffer) frame.image[0]).put(buffer);

        videoRecorder.record(frame, AV_PIX_FMT_RGB32_1);

        taskStatistics.poll();
    }

    /**
     * Set last pixels of image to {@link io.github.eoinkanro.filestovideosconverter.utils.BytesUtils#ZERO}
     * And save image
     *
     * @throws FFmpegFrameRecorder.Exception - if image can't be written into video
     */
    private void processLastPixels(FFmpegFrameRecorder videoRecorder) throws FFmpegFrameRecorder.Exception {
        if (tempRowIndex < tempRow.length) {
            for (int i = TeamRowIndex; i < tempRow.length; i++) {
                tempRow[i] = ZERO;
            }
            writeTempRowIntoImage();
        }

        if (pixelIndex < pixels.length) {
            for (int i = pixelIndex; i < pixels.length; i++) {
                pixels[i] = ZERO;
            }
            writeImageIntoVideo(videoRecorder);
        }
    }
}
