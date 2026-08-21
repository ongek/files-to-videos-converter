private void processFileDirectNV12(File video, OutputStream outputStream) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setVideoOption("hwaccel", "videotoolbox");
            
            // 【超重要】動画のネイティブフォーマット (YUV420P) を指定して sws_scale を 100% 抹消！
            grabber.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
            
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (frame.image == null || frame.image[0] == null) {
                    continue;
                }

                int imageWidth = frame.imageWidth;
                int imageHeight = frame.imageHeight;
                int rowStride = frame.imageStride; // 128B アライメントストライド

                ByteBuffer nativeBuffer = (ByteBuffer) frame.image[0];
                MemorySegment yPlaneSegment = MemorySegment.ofBuffer(nativeBuffer);

                // YUV420P の Y プレーン (frame.image[0]) をダイレクトに高速デコード
                decodeYPlaneBlitz(yPlaneSegment, imageWidth, imageHeight, rowStride, outputStream);

                taskStatistics.poll();
            }
        }
    }
