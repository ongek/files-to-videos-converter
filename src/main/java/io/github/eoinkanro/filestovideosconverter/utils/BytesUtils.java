package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

@Component
public class BytesUtils {

    // AWT依存を排除し、ダイレクトにARGBカラー定数を定義
    public static final int ONE = 0xFF000000;             // 黒 (Black / bit 1) = -16777216
    public static final int ZERO = 0xFFFFFFFF;            // 白 (White / bit 0) = -1
    public static final int ONE_MIN = ONE / 2 - 1;       // -8388609 (二値化の境界基準値)

    // 0〜255 (1バイト) のビット文字列キャッシュ (GCゼロ・O(1)アクセス)
    private static final String[] BIT_STRINGS = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            String bits = Integer.toBinaryString(i);
            BIT_STRINGS[i] = "0".repeat(8 - bits.length()) + bits;
        }
    }

    /**
     * 1バイトを8桁の2進数文字列へ変換 (例: 00000001)
     */
    public static String byteToBits(int aByte) {
        return BIT_STRINGS[aByte & 0xFF];
    }

    /**
     * 1ビット (0 or 1) を ARGB カラーピクセル (黒 or 白) へ変換
     */
    public static int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }

    /**
     * ピクセル合計値からビット (0 or 1) を判定
     *
     * @param pixelSum         集約されたピクセル値の合計
     * @param duplicateFactor  1ビットあたりのピクセル拡大率
     * @return 0 または 1
     */
    public static int pixelToBit(int pixelSum, int duplicateFactor) {
        if (duplicateFactor == 1) {
            return pixelSum > ONE_MIN ? 0 : 1;
        }
        long threshold = (long) duplicateFactor * duplicateFactor * ONE_MIN;
        return pixelSum > threshold ? 0 : 1;
    }

    /**
     * 【デコード高速化用】事前に計算した閾値を使って高速にビット判定
     *
     * @param pixelSum  集約されたピクセル値の合計
     * @param threshold calculateThreshold(duplicateFactor) で事前算出した閾値
     */
    public static int pixelToBitWithThreshold(long pixelSum, long threshold) {
        return pixelSum > threshold ? 0 : 1;
    }

    /**
     * duplicateFactor に基づく二値化閾値を計算 (フレームごとに1度だけ呼ぶ)
     */
    public static long calculateThreshold(int duplicateFactor) {
        return (duplicateFactor == 1)
                ? ONE_MIN
                : (long) duplicateFactor * duplicateFactor * ONE_MIN;
    }

    /**
     * RGB 各色バイト (0〜255) を 32bit ARGB 整数値に結合
     */
    public static int rgbToPixel(byte red, byte green, byte blue) {
        return 0xFF000000 | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }
}
