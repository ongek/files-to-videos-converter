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

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(processData));
             FFmpegFrameRecorder videoRecorder = new FFmpegFrameRecorder(fileUtils.getFilesToVideosResultFile(processData, lastZeroBytesCount),
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
        } catch (Exception e) {
            log.error(COMMON_EXCEPTION_DESCRIPTION, e);
            throw new TransformException(COMMON_EXCEPTION_DESCRIPTION, e);
        }

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
     * Init ffmpeg recorder that creates result video
     *
     * @param videoRecorder - ffmpeg recorder
     * @throws FFmpegFrameRecorder.Exception - if recorder can't be started
     */
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
        videoRecorder.setVideoBitrate(8000000); // 8Mbps

        videoRecorder.setAudioChannels(0);
        videoRecorder.setSampleRate(0);

        // Apple互換性を高める高速起動フラグ
        videoRecorder.setOption("movflags", "faststart");

        // -----------------------------------------------------------------
        // 【完全型確定版】
        // start()の前にAVFormatContextを先制生成してhvc1を仕込む
        // -----------------------------------------------------------------
        try {
            // 1. レコーダーの内部フィールド "oc" をリフレクションで取得
            java.lang.reflect.Field ocField = FFmpegFrameRecorder.class.getDeclaredField("oc");
            ocField.setAccessible(true);

            // 2. 先回りして出力文脈（AVFormatContext）をインスタンス化
            org.bytedeco.ffmpeg.avformat.AVFormatContext oc = new org.bytedeco.ffmpeg.avformat.AVFormatContext();
            
            // 3. 出力フォーマット（mp4）の判定
            org.bytedeco.ffmpeg.avformat.AVOutputFormat oformat = org.bytedeco.ffmpeg.global.avformat.av_guess_format("mp4", null, null);
            
            // 【修正箇所】null を (String) にキャストしてコンパイラの迷子を防止
            org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2(oc, oformat, (String) null, (String) null);
            
            // 4. レコーダーインスタンスに、先制生成した oc をインジェクション
            ocField.set(videoRecorder, oc);

            // 5. ビデオストリーム（0番目）を1本生やし、エンコーダ（hevc_videotoolbox）を割り当てる
            org.bytedeco.ffmpeg.avcodec.AVCodec codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name("hevc_videotoolbox");
            org.bytedeco.ffmpeg.avformat.AVStream videoStream = org.bytedeco.ffmpeg.global.avformat.avformat_new_stream(oc, codec);
            
            if (videoStream != null && videoStream.codecpar() != null) {
                // ヘッダーが物理的にファイルへ書き込まれる前に 'hvc1' (0x31637668) を絶対強制注入
                int hvc1Tag = 0x31637668;
                videoStream.codecpar().codec_tag(hvc1Tag);
                log.info("Pre-injected 'hvc1' tag into pre-allocated AVFormatContext successfully.");
            }

        } catch (Exception e) {
            log.warn("Failed to pre-inject hvc1 tag. Continuing with standard start.", e);
        }

        // 6. 準備完了した状態でスタート
        videoRecorder.start();
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
            for (int i = tempRowIndex; i < tempRow.length; i++) {
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
