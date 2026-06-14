package io.github.eoinkanro.filestovideosconverter.utils;

import org.springframework.stereotype.Component;

@Component
public class BytesUtils {

    // 1ピクセル（R+G+B）が「白」か「黒」かを分ける輝度閾値
    private static final int BRIGHTNESS_THRESHOLD = 382;

    /**
     * 3つの色成分から輝度の和を求めます。
     * Java 26のJITコンパイラが最もインライン化しやすいよう、
     * Byte.toUnsignedInt() を使用（内部的に & 0xFF と同等だがセマンティクスが明確）。
     */
    public static int getPixelBrightness(byte c1, byte c2, byte c3) {
        return Byte.toUnsignedInt(c1) + Byte.toUnsignedInt(c2) + Byte.toUnsignedInt(c3);
    }

    /**
     * ブロック全体の輝度合計から、最終的な1ビット（0か1）を判定します。
     */
    public static int pixelToBit(long totalBrightnessSum, int duplicateFactor) {
        // 重複度による総ピクセル数（int同士の乗算を一度だけ行う）
        long totalPixelsInBlock = (long) duplicateFactor * duplicateFactor;
        long threshold = totalPixelsInBlock * BRIGHTNESS_THRESHOLD;
        
        // 元のロジックを維持：閾値を超えたら0（白）、以下なら1（黒）
        return totalBrightnessSum > threshold ? 0 : 1;
    }

    /**
     * 高速なビット展開（Stringの生成を完全に排除するためのプリミティブ版も用意）
     * ※このメソッド自体は他の場所用として残しますが、デコーダー内では使いません。
     */
    public static String byteToBits(int aByte) {
        String bits = Integer.toBinaryString(aByte & 0xFF);
        int len = bits.length();
        if (len >= 8) return bits;
        return "0".repeat(8 - len) + bits;
    }

    public static int bitToPixel(int bit) {
        return bit == 1 ? 0xFF000000 : 0xFFFFFFFF;
    }
}
