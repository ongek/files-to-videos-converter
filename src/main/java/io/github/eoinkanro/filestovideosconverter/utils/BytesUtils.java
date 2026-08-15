package io.github.eoinkanro.filestovideosconverter.utils;

import java.awt.Color;

public final class BytesUtils {

    public static final int ONE = Color.BLACK.getRGB();   // -16777216
    public static final int ZERO = Color.WHITE.getRGB();  // -1

    private BytesUtils() {
        // インスタンス化防止
    }

    /**
     * ビット(1 or 0)をARGBピクセル値に変換 (旧コード互換用)
     */
    public static int bitToPixel(int bit) {
        return (bit == 1) ? ONE : ZERO;
    }
}
