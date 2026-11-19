package com.mideyy7.retrieve.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A module's colour: [accent] is a solid fill that carries white text (badges, timestamp tiles,
 * week bars), [container] a pale tint of it (folder circles), [onContainer] text or icons on that tint.
 */
@Immutable
data class ModuleColor(val accent: Color, val container: Color, val onContainer: Color)

private fun hue(accent: Long, container: Long, on: Long) = ModuleColor(Color(accent), Color(container), Color(on))

// Eight hues spaced around the wheel, each dark enough for white text on the solid fill (as on the
// landing page's module badges). Indigo is left out: it's the app's own colour.
private val Hues = listOf(
    hue(0xFF13836F, 0xFFD5F0EA, 0xFF0B4D41), // teal
    hue(0xFFB4610A, 0xFFFCEBD6, 0xFF6B3A06), // amber
    hue(0xFF7B3FBF, 0xFFEEE3FA, 0xFF4A2475), // purple
    hue(0xFF2E7D32, 0xFFDCEFDC, 0xFF1B4D1E), // green
    hue(0xFFC2185B, 0xFFFBDDE8, 0xFF7A0F39), // raspberry
    hue(0xFF0A6FB0, 0xFFD6E9F7, 0xFF064469), // ocean
    hue(0xFF8A6D00, 0xFFF6EDC8, 0xFF544200), // ochre
    hue(0xFF6D4C41, 0xFFEDE3DF, 0xFF432E27), // cocoa
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
    return Hues[LocalModuleHues.current[module.uppercase()] ?: moduleHueIndex(module)]
}
