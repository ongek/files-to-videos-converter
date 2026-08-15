package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

import java.awt.*;

@Component
public class BytesUtils {

    public static final int ONE = Color.BLACK.getRGB();       // -16777216 (0xFF000000)
    public static final int ONE_MIN = ONE / 2 - 1;           // -8388609
    public static final int ZERO = Color.white.getRGB();      // -1 (0xFFFFFFFF)

    // 0〜255 (1バイト) のビット文字列をあらかじめキャッシュ（メモリ割り当てゼロ・O(1)アクセス）
    private static final String[] BIT_STRINGS = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            String bits = Integer.toBinaryString(i);
            BIT_STRINGS[i] = "0".repeat(8 - bits.length()) + bits;
        }
    }

    /**
     * Transform byte to bits string
     * example: 00000001
     *
     * @param aByte - byte (0 ~ 255)
     * @return - 8-length bits string
     */
    public String byteToBits(int aByte) {
        return BIT_STRINGS[aByte & 0xFF];
    }

    /**
     * Transform bit pixel
     *
     * @param bit - bit
     * @return - pixel
     */
    public int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }

    /**
     * Transform pixel to bit
     *
     * @param pixel - pixel
     * @param duplicateFactor - duplicate factor of pixels per bit.
     * @return - bit (0 or 1)
     */
    public int pixelToBit(int pixel, int duplicateFactor) {
        // duplicateFactor == 1 の場合は乗算を回避して定数比較
        long oneMin = (duplicateFactor == 1)
                ? ONE_MIN
                : (long) duplicateFactor * duplicateFactor * ONE_MIN;

        return pixel > oneMin ? 0 : 1;
    }

    /**
     * RGBバイト列をARGB整数値に結合（最内層用高速化）
     */
    public int pixelToBit(byte red, byte green, byte blue) {
        return 0xFF000000 | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }
}
