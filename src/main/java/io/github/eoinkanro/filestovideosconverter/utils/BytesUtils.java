package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

@Component
public final class BytesUtils {

    // AWT非依存の 32-bit ARGB カラー定数 (数学的線形性を完全維持)
    public static final int ONE = 0xFF000000;             // 黒 (Black / bit 1) = -16777216
    public static final int ZERO = 0xFFFFFFFF;            // 白 (White / bit 0) = -1
    public static final int ONE_MIN = ONE / 2 - 1;       // -8388609 (輝度127.5の完全中央値)

    // 0〜255 (1バイト) のビット文字列キャッシュ (GCゼロ・O(1)アクセス)
    private static final String[] BIT_STRINGS = new String[256];

    // duplicateFactor (1〜64) の事前計算閾値テーブル (ホットループ内の乗算をゼロ化)
    private static final long[] THRESHOLD_TABLE = new long[65];

    static {
        // 文字列キャッシュの高速構築 (中間 String オブジェクトのアロケーションを最小化)
        for (int i = 0; i < 256; i++) {
            char[] chars = new char[8];
            for (int b = 0; b < 8; b++) {
                chars[b] = ((i & (1 << (7 - b))) != 0) ? '1' : '0';
            }
            BIT_STRINGS[i] = new String(chars);
        }

        // 閾値テーブルの事前計算 (df=1〜64)
        THRESHOLD_TABLE[1] = ONE_MIN;
        for (int df = 2; df <= 64; df++) {
            THRESHOLD_TABLE[df] = (long) df * df * ONE_MIN;
        }
    }

    /**
     * 1バイトを8桁の2進数文字列へ変換 (例: 00000001)
     */
    public static String byteToBits(int aByte) {
        return BIT_STRINGS[aByte & 0xFF];
    }

    /**
     * 1ビット (0 or 1) を ARGB カラーピクセルへ変換
     * Apple M4 (AArch64) の CSEL 命令に直結し、1サイクル・完全Branchlessで実行
     */
    public static int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }

    /**
     * ピクセル合計値からビット (0 or 1) を判定 (数学的線形性による耐ノイズ判定)
     */
    public static int pixelToBit(int pixelSum, int duplicateFactor) {
        return pixelSum > calculateThreshold(duplicateFactor) ? 0 : 1;
    }

    /**
     * 【互換用】RGB 各色バイト (0〜255) を 32bit ARGB 整数値に結合
     * ※ 既存の VideosToFilesTransformerTask 等からの呼び出し互換性を維持
     */
    public static int pixelToBit(byte red, byte green, byte blue) {
        return rgbToPixel(red, green, blue);
    }

    /**
     * RGB 各色バイト (0〜255) を 32bit ARGB 整数値に結合
     */
    public static int rgbToPixel(byte red, byte green, byte blue) {
        return ONE | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    /**
     * 事前に計算・取得した閾値を使って高速にビット判定
     */
    public static int pixelToBitWithThreshold(long pixelSum, long threshold) {
        return pixelSum > threshold ? 0 : 1;
    }

    /**
     * duplicateFactor に基づく二値化閾値を O(1) テーブル引きで取得
     */
    public static long calculateThreshold(int duplicateFactor) {
        if (duplicateFactor >= 1 && duplicateFactor <= 64) {
            return THRESHOLD_TABLE[duplicateFactor];
        }
        return (long) duplicateFactor * duplicateFactor * ONE_MIN;
    }

    /**
     * 【デコード最速化】8つのピクセル合計値から 1バイト(0〜255) を完全分岐レスで合成
     * AArch64 の CSET 命令 8 連発により、分岐予測ミス 0% で 1 バイトをパック
     */
    public static byte pixelsToByte(long p0, long p1, long p2, long p3,
                                    long p4, long p5, long p6, long p7,
                                    long threshold) {
        int b = ((p0 > threshold ? 0 : 1) << 7)
              | ((p1 > threshold ? 0 : 1) << 6)
              | ((p2 > threshold ? 0 : 1) << 5)
              | ((p3 > threshold ? 0 : 1) << 4)
              | ((p4 > threshold ? 0 : 1) << 3)
              | ((p5 > threshold ? 0 : 1) << 2)
              | ((p6 > threshold ? 0 : 1) << 1)
              |  (p7 > threshold ? 0 : 1);
        return (byte) b;
    }

    /**
     * 【デコード一括バッチ処理】ピクセル合計配列からバイト配列へ一気に復元 (M4 パイプライン最適化)
     */
    public static void batchPixelsToBytes(long[] pixelSums, int pixelOffset,
                                          byte[] outBytes, int byteOffset,
                                          int byteCount, long threshold) {
        int pIdx = pixelOffset;
        for (int i = 0; i < byteCount; i++) {
            outBytes[byteOffset + i] = pixelsToByte(
                    pixelSums[pIdx],     pixelSums[pIdx + 1], pixelSums[pIdx + 2], pixelSums[pIdx + 3],
                    pixelSums[pIdx + 4], pixelSums[pIdx + 5], pixelSums[pIdx + 6], pixelSums[pIdx + 7],
                    threshold
            );
            pIdx += 8;
        }
    }
}
