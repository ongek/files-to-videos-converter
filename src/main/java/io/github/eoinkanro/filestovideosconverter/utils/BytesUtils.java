package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

@Component
public final class BytesUtils {

    // 32-bit ARGB カラー定数 (数学的線形性を完全維持)
    public static final int ONE = 0xFF000000;             // 黒 (Black / bit 1) = -16777216
    public static final int ZERO = 0xFFFFFFFF;            // 白 (White / bit 0) = -1
    public static final int ONE_MIN = ONE / 2 - 1;       // -8388609 (輝度127.5の基準境界値)

    private static final String[] BIT_STRINGS = new String[256];
    private static final long[] THRESHOLD_TABLE = new long[65];

    // エンコード高速化用フラットLUT
    public static final int[] BIT_TO_PIXEL_FLAT_LUT = new int[256 * 8];

    static {
        for (int i = 0; i < 256; i++) {
            char[] chars = new char[8];
            for (int b = 0; b < 8; b++) {
                chars[b] = ((i & (1 << (7 - b))) != 0) ? '1' : '0';
            }
            BIT_STRINGS[i] = new String(chars);
        }

        THRESHOLD_TABLE[1] = ONE_MIN;
        for (int df = 2; df <= 64; df++) {
            THRESHOLD_TABLE[df] = (long) df * df * ONE_MIN;
        }

        for (int b = 0; b < 256; b++) {
            for (int bit = 0; bit < 8; bit++) {
                BIT_TO_PIXEL_FLAT_LUT[(b << 3) + bit] = ((b & (1 << (7 - bit))) != 0) ? ONE : ZERO;
            }
        }
    }

    // =========================================================================
    // 1. エンコード用 (Bit -> Pixel)
    // =========================================================================

    public static String byteToBits(int aByte) {
        return BIT_STRINGS[aByte & 0xFF];
    }

    /**
     * 【M4 CSEL】1ビット -> 32bit ピクセル (ジャンプ分岐ゼロ・1サイクル)
     */
    public static int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }

    // =========================================================================
    // 2. デコード用ピクセル正規化 (入力フォーマット別)
    // =========================================================================

    /**
     * 【形式1: 3バイトRGB】バイト列 (R, G, B) から 32bit ARGB を合成 (既存互換)
     */
    public static int pixelToBit(byte red, byte green, byte blue) {
        return rgbToPixel(red, green, blue);
    }

    public static int rgbToPixel(byte red, byte green, byte blue) {
        return ONE | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    /**
     * 【形式2: 0x00RRGGBB / 0xFFRRGGBB】
     * 最上位バイトが 00 (アルファなし) または不定な 24-bit RGB 整数値を、安全に 32-bit ARGB (0xFFRRGGBB) へ正規化
     */
    public static int rgbToPixel(int rawRgb) {
        // 下位24bit (RGB) のみを抽出し、アルファ 0xFF を確実に付与
        return ONE | (rawRgb & 0x00FFFFFF);
    }

    /**
     * 【形式3: Little-Endian RGBA / BGRA メモリ直読】
     * M4 ネイティブメモリから 32bit 単位で一括ロードした値 (最上位バイトがアルファ) を安全に ARGB 化
     */
    public static int nativeMemoryToPixel(int rawMemoryInt) {
        // 白黒 (R≈G≈B) の場合、下位24bitは同一なのでアルファのみ 0xFF で上書きすれば ARGB と完全同等
        return ONE | (rawMemoryInt & 0x00FFFFFF);
    }

    /**
     * 【形式4: 8-bit グレースケール (YUVのY値 / GRAY8)】
     * 1バイトの輝度値 (0〜255) を直接 ARGB 整数値に変換
     */
    public static int grayToPixel(int grayByte) {
        int v = grayByte & 0xFF;
        return ONE | (v << 16) | (v << 8) | v;
    }

    // =========================================================================
    // 3. デコード二値化判定 (Pixel -> Bit)
    // =========================================================================

    /**
     * ピクセル合計値からビット (0 or 1) を判定 (大容量 duplicateFactor 対応)
     */
    public static int pixelToBit(long pixelSum, int duplicateFactor) {
        return pixelSum > calculateThreshold(duplicateFactor) ? 0 : 1;
    }

    public static int pixelToBit(int pixelSum, int duplicateFactor) {
        return pixelToBit((long) pixelSum, duplicateFactor);
    }

    public static int pixelToBitWithThreshold(long pixelSum, long threshold) {
        return pixelSum > threshold ? 0 : 1;
    }

    public static long calculateThreshold(int duplicateFactor) {
        if (duplicateFactor >= 1 && duplicateFactor <= 64) {
            return THRESHOLD_TABLE[duplicateFactor];
        }
        return (long) duplicateFactor * duplicateFactor * ONE_MIN;
    }

    /**
     * 【M4 AArch64 CSET】8つのピクセル合計値から 1バイトを完全分岐レスで合成
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
     * 【デコード一括バッチ処理】
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
