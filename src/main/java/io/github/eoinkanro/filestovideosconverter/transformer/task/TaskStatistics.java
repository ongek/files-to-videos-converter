package io.github.eoinkanro.filestovideosconverter.transformer.task;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * 処理フレーム数と正確な FPS を極小オーバーヘッドで計測・ログ出力するクラス
 */
@Log4j2
public final class TaskStatistics {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    // 32フレームごとにナノ秒チェック (ビットマスク判定用: 32 - 1)
    private static final int CHECK_INTERVAL_MASK = 31; 

    @Setter
    private String filePath;

    private long startNanos;        // タスク全体の開始時刻
    private long intervalBeginNanos; // 1秒インターバルの開始時刻
    private long intervalFrames;    // インターバル内のフレーム数
    private long totalFrames;       // 総フレーム数
    private long lastReportedFps;   // 直近の FPS 値

    /**
     * ホットループから毎フレーム呼ばれる計測メソッド (完全インライン化対応)
     */
    public void poll() {
        totalFrames++;
        intervalFrames++;

        // 32フレームに1回だけビット演算で nanoTime() を取得 (CPU負荷ゼロ)
        if ((totalFrames & CHECK_INTERVAL_MASK) == 0 || intervalBeginNanos == 0) {
            long currentNanos = System.nanoTime();

            if (intervalBeginNanos == 0) {
                startNanos = currentNanos;
                intervalBeginNanos = currentNanos;
            } else {
                long elapsed = currentNanos - intervalBeginNanos;
                if (elapsed >= ONE_SECOND_NANOS) {
                    // 経過時間に基づき厳密な FPS を算出
                    lastReportedFps = Math.round((intervalFrames * (double) ONE_SECOND_NANOS) / elapsed);
                    log.info("{} | FPS: {} | Total frames: {}", filePath, lastReportedFps, totalFrames);
                    
                    intervalBeginNanos = currentNanos;
                    intervalFrames = 0;
                }
            }
        }
    }

    /**
     * タスク終了時の最終統計ログ (全体の平均 FPS を正確に出力)
     */
    public void logResult() {
        long totalElapsed = System.nanoTime() - startNanos;
        long averageFps = (totalElapsed > 0)
                ? Math.round((totalFrames * (double) ONE_SECOND_NANOS) / totalElapsed)
                : lastReportedFps;

        log.info("{} | Avg FPS: {} | Total frames: {}", filePath, averageFps, totalFrames);
    }
}
