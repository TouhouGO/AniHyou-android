package com.axiel7.anihyou.core.localization

import java.io.DataInputStream
import java.text.Normalizer
import java.util.Locale

object SearchTextNormalizer {
    /** Lossy search key only; never use it as a displayed or persisted source name. */
    fun normalize(value: String): String = normalize(value, Character::isLetterOrDigit)

    internal fun normalize(value: String, isLetterOrDigit: (Int) -> Boolean): String = buildString {
        val text = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        text.codePoints().filter { isStableIdeograph(it) || isLetterOrDigit(it) }
            .forEach { appendCodePoint(SearchFold.fold(it)) }
    }

    /** Detects Han code points using fixed CJK ranges and the platform Han script table. */
    fun containsHan(value: String): Boolean = value.codePoints().anyMatch(::isHanCodePoint)
}

// Fixed Unicode CJK block ranges, independent of Android's Unicode database.
// Includes unassigned positions within these blocks so later assigned ideographs
// work on older OS releases. Other scripts retain the platform's classification.
internal fun isHanCodePoint(point: Int): Boolean = isStableIdeograph(point) || when (point) {
    in 0x2E80..0x2EFF, // CJK Radicals Supplement
    in 0x2F00..0x2FDF, // Kangxi Radicals
    0x3005, 0x3007, 0x303B, // Han iteration marks and ideographic zero
    in 0x3021..0x3029, in 0x3038..0x303A, // Hangzhou numerals
    in 0x16FE2..0x16FE3, in 0x16FF0..0x16FF1, // Han marks
    -> true
    else -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
}

// The bundled mapping contains only ideographs; keep punctuation filtering
// consistent with the pre-existing search normalization.
internal fun isStableIdeograph(point: Int): Boolean = when (point) {
    in 0x3400..0x4DBF, // Extension A
    in 0x4E00..0x9FFF, // Unified Ideographs
    in 0xF900..0xFAFF, // Compatibility Ideographs
    in 0x20000..0x2A6DF, // Extension B
    in 0x2A700..0x2B73F, // Extension C
    in 0x2B740..0x2B81F, // Extension D
    in 0x2B820..0x2CEAF, // Extension E
    in 0x2CEB0..0x2EBEF, // Extension F
    in 0x2EBF0..0x2EE5F, // Extension I
    in 0x2F800..0x2FA1F, // Compatibility Ideographs Supplement
    in 0x30000..0x3134F, // Extension G
    in 0x31350..0x323AF, // Extension H
    -> true
    else -> false
}

private object SearchFold {
    private val pairs: Pair<IntArray, IntArray> = DataInputStream(
        requireNotNull(SearchFold::class.java.getResourceAsStream("/localization/opencc/ts-characters.bin"))
    ).use { input ->
        val count = input.readInt()
        val keys = IntArray(count)
        val values = IntArray(count)
        repeat(count) { keys[it] = input.readInt(); values[it] = input.readInt() }
        keys to values
    }

    fun fold(point: Int): Int {
        val index = pairs.first.binarySearch(point)
        return if (index >= 0) pairs.second[index] else point
    }
}
