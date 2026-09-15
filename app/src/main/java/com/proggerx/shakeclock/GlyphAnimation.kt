package com.proggerx.shakeclock

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for animation data exported from the Glyph Museum web editor
 * (https://editor.glyphmuseum.com). "Phone 4a" profile, `v = 4`.
 *
 * JSON shape:
 * {
 *   "v": 4,
 *   "frames": [ { "d": 100, "p": [ ... 137 opacities 0..255 ... ] }, ... ]
 * }
 *
 * The 137 values are row-major over the *active* (circular) pixels of the
 * 13x13 matrix, with row widths:
 *   [5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5]
 */
object GlyphAnimation {

    const val SIZE = 13
    private const val MAX_BRIGHTNESS = 4095
    private const val DEFAULT_FRAME_MS = 100L
    private val ROW_WIDTHS = intArrayOf(5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5)

    data class Frame(val durationMs: Long, val pixels: IntArray)

    fun parse(source: String): List<Frame>? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        return parseJson(trimmed) ?: parseCsv(trimmed)
    }

    private fun parseJson(source: String): List<Frame>? = try {
        val root = JSONObject(source)
        if (root.optInt("v", 4) != 4) {
            null
        } else {
            root.optJSONArray("frames")?.let { buildList(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun buildList(frames: JSONArray): List<Frame>? {
        if (frames.length() == 0) return null
        val result = ArrayList<Frame>(frames.length())
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index) ?: continue
            val values = frame.optJSONArray("p") ?: continue
            val duration = frame.optLong("d", DEFAULT_FRAME_MS).coerceAtLeast(1L)
            result.add(Frame(duration, toMatrix(values)))
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseCsv(source: String): List<Frame>? {
        if (!source.startsWith("[") && !source.contains(',')) return null
        val values = JSONArray(
            source.trim().removePrefix("[").removeSuffix("]")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(prefix = "[", postfix = "]")
        )
        if (values.length() == 0) return null
        return listOf(Frame(DEFAULT_FRAME_MS, toMatrix(values)))
    }

    private fun toMatrix(values: JSONArray): IntArray {
        val matrix = IntArray(SIZE * SIZE)
        var index = 0
        for (row in 0 until SIZE) {
            val width = ROW_WIDTHS[row]
            val startColumn = (SIZE - width) / 2
            for (column in 0 until width) {
                if (index >= values.length()) return matrix
                val opacity = values.optInt(index, 0).coerceIn(0, 255)
                index++
                matrix[row * SIZE + startColumn + column] = opacity * MAX_BRIGHTNESS / 255
            }
        }
        return matrix
    }
}
