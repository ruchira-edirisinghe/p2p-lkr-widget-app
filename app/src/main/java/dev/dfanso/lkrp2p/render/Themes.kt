package dev.dfanso.lkrp2p.render

import android.content.Context
import android.os.Build

/**
 * Concrete colours for one theme. Every theme is dark: the glow card is the
 * look of the app, so a theme changes its hue, not its structure.
 */
data class ThemeColors(
    /** The card and screen ground. */
    val base: Int,
    /** The radial glow above the top edge, and its fade. */
    val glow: Int,
    val glowMid: Int,
    /** The accent: rising prices, selected controls, the chart line. */
    val up: Int,
    val down: Int,
    /** The USDT coin and other filled badges. */
    val coin: Int,
) {
    /** Translucent [base] for the widget, at [percent] opacity. */
    fun withOpacity(percent: Int): ThemeColors {
        val alpha = (percent.coerceIn(0, 100) * 255 / 100) shl 24
        fun fade(color: Int) = (color and 0x00FFFFFF) or
            ((((color ushr 24) * percent.coerceIn(0, 100)) / 100) shl 24)
        return copy(base = (base and 0x00FFFFFF) or alpha, glow = fade(glow), glowMid = fade(glowMid))
    }
}

enum class ColorTheme(val key: String, val label: String, val preset: ThemeColors?) {
    EMERALD("emerald", "Emerald", ThemeColors(
        base = 0xFF131313.toInt(), glow = 0xFF13804A.toInt(), glowMid = 0x8C0E5233.toInt(),
        up = 0xFF3BE29A.toInt(), down = 0xFFFF6B6B.toInt(), coin = 0xFF1F9D78.toInt(),
    )),
    OCEAN("ocean", "Ocean", ThemeColors(
        base = 0xFF10131A.toInt(), glow = 0xFF1660C2.toInt(), glowMid = 0x8C0C3672.toInt(),
        up = 0xFF5CC8FF.toInt(), down = 0xFFFF7A85.toInt(), coin = 0xFF2A7FD4.toInt(),
    )),
    AMETHYST("amethyst", "Amethyst", ThemeColors(
        base = 0xFF141118.toInt(), glow = 0xFF6D2EC7.toInt(), glowMid = 0x8C3B1A72.toInt(),
        up = 0xFFC09BFF.toInt(), down = 0xFFFF7097.toInt(), coin = 0xFF7C4DDB.toInt(),
    )),
    SUNSET("sunset", "Sunset", ThemeColors(
        base = 0xFF171210.toInt(), glow = 0xFFC2471A.toInt(), glowMid = 0x8C6E240C.toInt(),
        up = 0xFFFFB35C.toInt(), down = 0xFFFF5C7C.toInt(), coin = 0xFFD9682B.toInt(),
    )),
    ROSE("rose", "Rose", ThemeColors(
        base = 0xFF171114.toInt(), glow = 0xFFB51A5E.toInt(), glowMid = 0x8C690E37.toInt(),
        up = 0xFFFF8FC4.toInt(), down = 0xFFFFB066.toInt(), coin = 0xFFC93478.toInt(),
    )),
    GOLD("gold", "Gold", ThemeColors(
        base = 0xFF141310.toInt(), glow = 0xFF8F6A0E.toInt(), glowMid = 0x8C4A3706.toInt(),
        up = 0xFFFFD166.toInt(), down = 0xFFFF6B6B.toInt(), coin = 0xFFB8891A.toInt(),
    )),
    GRAPHITE("graphite", "Graphite", ThemeColors(
        base = 0xFF141414.toInt(), glow = 0xFF4A4A4A.toInt(), glowMid = 0x8C262626.toInt(),
        up = 0xFFE4E4E4.toInt(), down = 0xFFFF7B7B.toInt(), coin = 0xFF5C5C5C.toInt(),
    )),

    /** Pure black with no glow, for OLED screens. */
    MIDNIGHT("midnight", "Midnight (OLED)", ThemeColors(
        base = 0xFF000000.toInt(), glow = 0x00000000, glowMid = 0x00000000,
        up = 0xFF3BE29A.toInt(), down = 0xFFFF6B6B.toInt(), coin = 0xFF1F9D78.toInt(),
    )),

    /** Material You: the accent follows the wallpaper on Android 12 and later. */
    WALLPAPER("wallpaper", "Wallpaper (Material You)", null);

    fun colors(context: Context): ThemeColors {
        preset?.let { return it }
        if (Build.VERSION.SDK_INT < 31) return EMERALD.preset!!
        fun c(id: Int) = context.getColor(id)
        return ThemeColors(
            base = c(android.R.color.system_neutral1_900),
            glow = c(android.R.color.system_accent1_700),
            glowMid = (c(android.R.color.system_accent1_800) and 0x00FFFFFF) or 0x8C000000.toInt(),
            up = c(android.R.color.system_accent1_200),
            down = 0xFFFF6B6B.toInt(),
            coin = c(android.R.color.system_accent1_500),
        )
    }

    companion object {
        val DEFAULT = EMERALD
        fun fromKey(key: String?): ColorTheme? = entries.firstOrNull { it.key == key }
    }
}
