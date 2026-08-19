package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

@Component
public class BytesUtils {

    // AWT非依存の 32-bit ARGB カラー定数
    public static final int ONE = 0xFF000000;             // 黒 (Black / bit 1) = -16777216
    public static final int ZERO = 0xFFFFFFFF;            // 白 (White / bit 0) = -1
    public static final int ONE_MIN = ONE / 2 - 1;       // -8388609 (基準境界値)

    // 【M4 分岐レス化】三項演算子を排除し、L1キャッシュ直結の 2 要素 LUT
    private static final int[] BIT_TO_PIXEL_LUT = { ZERO, ONE };

    // 0〜255 (1バイト) のビット文字列キャッシュ (GCゼロ・O(1)アクセス)
    private static final String[] BIT_STRINGS = new String[256];

    // duplicateFactor (1〜64) の事前計算閾値テーブル (乗算コストをゼロ化)
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

        // 閾値テーブルの事前計算
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
     * 1ビット (0 or 1) を ARGB カラーピクセルへ変換 (完全分岐レス)
     */
    public static int bitToPixel(int bit) {
        return BIT_TO_PIXEL_LUT[bit & 1];
    }

    /**
     * ピクセル合計値からビット (0 or 1) を判定
     */
    public static int pixelToBit(int pixelSum, int duplicateFactor) {
        long threshold = calculateThreshold(duplicateFactor);
        return pixelSum > threshold ? 0 : 1;
    }

    /**
     * 【互換用】RGB 各色バイト (0〜255) を 32bit ARGB 整数値に結合
     */
    public static int pixelToBit(byte red, byte green, byte blue) {
        return rgbToPixel(red, green, blue);
    }

    /**
     * RGB 各色バイト (0〜255) を 32bit ARGB 整数値に結合
     */
    public static int rgbToPixel(byte red, byte green, byte blue) {
        return 0xFF000000 | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
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
     * 【デコード最内ループ高速化】8つのピクセル合計値から 1バイト(0〜255) を直接ビットパック復元
     * 文字列や中間配列を一切介さず、M4 のレジスタ上で 1バイトを組み立てます。
     */
    public static byte pixelsToByte(long p0, long p1, long p2, long p3,
                                    long p4, long p5, long p6, long p7,
                                    long threshold) {
        int b = 0;
        if (p0 <= threshold) b |= 0x80;
        if (p1 <= threshold) b |= 0x40;
        if (p2 <= threshold) b |= 0x20;
        if (p3 <= threshold) b |= 0x10;
        if (p4 <= threshold) b |= 0x08;
        if (p5 <= threshold) b |= 0x04;
        if (p6 <= threshold) b |= 0x02;
        if (p7 <= threshold) b |= 0x01;
        return (byte) b;
    }
}
