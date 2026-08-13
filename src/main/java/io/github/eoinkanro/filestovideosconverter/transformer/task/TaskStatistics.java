package io.github.eoinkanro.filestovideosconverter.transformer.task;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * It counts number of processed frames and logs this info
 */
@Log4j2
public class TaskStatistics {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final int CHECK_INTERVAL_FRAMES = 10; // 10フレームごとに時刻チェック

    @Setter
    private String filePath;
    private long beginNanos;

    private long framesPerSecond;
    private long totalFrames;

    public void poll() {
        totalFrames++;
        framesPerSecond++;

        // 毎回 System.currentTimeMillis() を呼ばず、一定フレームごとに時刻チェック
        if (totalFrames % CHECK_INTERVAL_FRAMES == 0 || beginNanos == 0) {
            long currentNanos = System.nanoTime();

            if (beginNanos == 0) {
                beginNanos = currentNanos;
            } else if (currentNanos - beginNanos >= ONE_SECOND_NANOS) {
                logResult();
                beginNanos = currentNanos;
                framesPerSecond = 0;
            }
        }
    }

    public void logResult() {
        log.info("{} | FPS: {} | Total frames: {}", filePath, framesPerSecond, totalFrames);
    }
}
