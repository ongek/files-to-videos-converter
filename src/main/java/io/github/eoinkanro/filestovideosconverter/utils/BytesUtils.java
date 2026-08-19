package io.github.eoinkanro.filestovideosconverter.utils;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.springframework.stereotype.Component;

@Component
public final class BytesUtils {

    // 32-bit ARGB カラー定数
    public static final int ONE = 0xFF000000;             // 黒 (Black / bit 1) = -16777216
    public static final int ZERO = 0xFFFFFFFF;            // 白 (White / bit 0) = -1
    public static final int ONE_MIN = ONE / 2 - 1;       // -8388609 (基準境界値)

    // 【M4 64-bit 結合定数】2ピクセルを一撃でメモリ展開するための long テーブル
    public static final long PIXEL_PAIR_00 = 0xFFFFFFFF_FFFFFFFFL; // 白・白
    public static final long PIXEL_PAIR_01 = 0xFFFFFFFF_FF000000L; // 白・黒
    public static final long PIXEL_PAIR_10 = 0xFF000000_FFFFFFFFL; // 黒・白
    public static final long PIXEL_PAIR_11 = 0xFF000000_FF000000L; // 黒・黒

    private static final String[] BIT_STRINGS = new String[256];
    private static final long[] THRESHOLD_TABLE = new long[65];

    // 【Java 26 Vector API】M4 NEON 用 256-bit / 128-bit ベクトル種別
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;

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
    }

    public static String byteToBits(int aByte) {
        return BIT_STRINGS[aByte & 0xFF];
    }

    /**
     * 【M4 CSEL】1ビット -> 32bit ピクセル (ジャンプ分岐ゼロ・1サイクル)
     */
    public static int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }

    /**
     * ピクセル合計値からビット (0 or 1) を判定
     */
    public static int pixelToBit(int pixelSum, int duplicateFactor) {
        return pixelSum > calculateThreshold(duplicateFactor) ? 0 : 1;
    }

    /**
     * 【互換用】RGB 各色バイト -> 32bit ARGB
     */
    public static int pixelToBit(byte red, byte green, byte blue) {
        return rgbToPixel(red, green, blue);
    }

    public static int rgbToPixel(byte red, byte green, byte blue) {
        return ONE | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
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
     * 【AArch64 CSET】8ピクセル -> 1バイト合成 (スカラ最速版)
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
     * 【Java 26 Vector API (NEON)】8ピクセル一括 SIMD 比較デコード
     * M4 のベクトルレジスタで 8要素を並列比較し、1命令で 8bit マスクとして抽出
     */
    public static byte vectorPixelsToByte(int[] pixels, int offset, int threshold) {
        // M4 の NEON ベクトルレジスタに 8 ピクセルを一括ロード
        IntVector vec = IntVector.fromArray(INT_SPECIES, pixels, offset);
        // 並列しきい値比較 (pixel <= threshold なら黒=1)
        VectorMask<Integer> mask = vec.compare(VectorOperators.LE, threshold);
        // マスクを 1 発で byte (8bit) に変換 (ビット順序を補正)
        return (byte) Integer.reverse( (int) mask.toLong() << 24 );
    }

    /**
     * 【一括バッチ処理】M4 パイプライン最適化ループ
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
