package com.mideyy7.retrieve.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A module's accent: [accent] for dots and bars, [container]/[onContainer] for its badge. */
@Immutable
data class ModuleColor(val accent: Color, val container: Color, val onContainer: Color)

private data class Hue(val light: ModuleColor, val dark: ModuleColor)

private fun hue(accentL: Long, containerL: Long, onL: Long, accentD: Long, containerD: Long, onD: Long) = Hue(
    ModuleColor(Color(accentL), Color(containerL), Color(onL)),
    ModuleColor(Color(accentD), Color(containerD), Color(onD)),
)

// Eight hues spaced around the wheel, tuned so the light badges read with dark text and the dark
// ones with light text. Indigo is left out: it's the app's own colour.
private val Hues = listOf(
    hue(0xFF00897B, 0xFFC8F2EC, 0xFF00201C, 0xFF5DD6C6, 0xFF004F47, 0xFFC8F2EC), // teal
    hue(0xFFD84315, 0xFFFFDBD0, 0xFF3A0B00, 0xFFFFB59E, 0xFF7A2A0A, 0xFFFFDBD0), // vermilion
    hue(0xFF7B1FA2, 0xFFF4D9FF, 0xFF2E004C, 0xFFE3B5FF, 0xFF5B1683, 0xFFF4D9FF), // purple
    hue(0xFF2E7D32, 0xFFCDEFCB, 0xFF002204, 0xFF8FD88E, 0xFF14521A, 0xFFCDEFCB), // green
    hue(0xFFC2185B, 0xFFFFD9E2, 0xFF3E0019, 0xFFFFB1C8, 0xFF7E0D3B, 0xFFFFD9E2), // raspberry
    hue(0xFF0277BD, 0xFFCFE5FF, 0xFF001D35, 0xFF98CBFF, 0xFF004A78, 0xFFCFE5FF), // ocean
    hue(0xFF9E7700, 0xFFFFE08A, 0xFF251A00, 0xFFF0C030, 0xFF5A4300, 0xFFFFE08A), // ochre
    hue(0xFF5D4037, 0xFFF2DED6, 0xFF2C1610, 0xFFE0BFB3, 0xFF4E342C, 0xFFF2DED6), // cocoa
)

/**
 * Which hue a module gets. Depends only on the code, so COMP21111 is the same colour on every
 * screen and every launch. Codes are case-folded, matching how modules are stored.
 */
fun moduleHueIndex(module: String): Int {
    // FNV-1a rather than String.hashCode: neighbouring codes (COMP21111, COMP21112) spread better.
    var h = 0x811C9DC5.toInt()
    for (ch in module.uppercase()) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return Math.floorMod(h, Hues.size)
}

/**
 * Gives each of the library's modules its own hue where possible: modules take their hashed hue in
 * code order, and one whose hue is already taken moves to the next free one. With five modules and a
 * plain hash, two pairs collided on the test library; this keeps them apart. Adding a module can shift
 * the colour of a module that sorts after it, which is rare and harmless.
 */
fun assignModuleHues(modules: Collection<String>): Map<String, Int> {
    val taken = BooleanArray(Hues.size)
    val out = LinkedHashMap<String, Int>()
    for (m in modules.map { it.uppercase() }.distinct().sorted()) {
        var i = moduleHueIndex(m)
        if (taken.all { it }) taken.fill(false)
        while (taken[i]) i = (i + 1) % Hues.size
        taken[i] = true
        out[m] = i
    }
    return out
}

/** The library's module-to-hue map, provided once at the top of the app. */
val LocalModuleHues = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

@Composable
fun moduleColor(module: String): ModuleColor {
    val hue = Hues[LocalModuleHues.current[module.uppercase()] ?: moduleHueIndex(module)]
    return if (RetrieveColors.extra.dark) hue.dark else hue.light
}
