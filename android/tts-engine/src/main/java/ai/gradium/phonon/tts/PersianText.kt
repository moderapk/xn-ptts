package ai.gradium.phonon.tts

/**
 * Persian text normalization for Cilla-mini, a full port of the model's own
 * normalize_fa.py (same steps, same order).
 *
 * The model was trained on text where numbers were already spelled out as
 * words, and the author's own speak_fa.py normalizes every input the same
 * way. Raw digits reach the model as noise: "۳۰۰" comes out garbled on the
 * phone while "سیصد" speaks cleanly (verified 2026-11-20 with the phone's
 * exact generation loop). So before the text reaches the engine, digit runs
 * become Persian words and Arabic letter forms are folded onto Persian ones.
 *
 * Only used for fa: other languages keep the Rust core's own normalization.
 * Pure Kotlin, no Android dependency, so it can be tested on the JVM.
 */
object PersianText {
    private val ONES = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val TEENS = arrayOf(
        "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده",
    )
    private val TENS = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val HUNDREDS = arrayOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد",
    )
    private val SCALES = listOf(
        1_000_000_000_000L to "تریلیون",
        1_000_000_000L to "میلیارد",
        1_000_000L to "میلیون",
        1_000L to "هزار",
    )
    private val FRACTION_UNITS = mapOf(1 to "دهم", 2 to "صدم", 3 to "هزارم")
    private const val MAX_NUMBER_DIGITS = 12

    /** The 32 letters of the Persian alphabet + ئ, the spelling the model was trained on. */
    private const val PERSIAN_LETTERS = "آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیئ"
    /** Punctuation the model saw in training (carries pauses / question intonation). */
    private const val KEPT_PUNCT = ".،؛؟!:"

    private fun isPersianLetter(ch: Char): Boolean = ch in PERSIAN_LETTERS
    private fun isAllowed(ch: Char): Boolean =
        isPersianLetter(ch) || ch == '\u200C' || ch in KEPT_PUNCT || ch == ' '

    /** Bidi controls, BOM and every zero-width mark EXCEPT U+200C (ZWNJ). */
    private fun isInvisible(ch: Char): Boolean =
        ch == '\u200B' || ch == '\u200D' || ch == '\u200E' || ch == '\u200F' ||
            ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' ||
            ch == '\uFEFF' || ch == '\u00AD'

    /** Harakat / tashdid / sukun / superscript alef and similar combining marks. */
    private fun isDiacritic(ch: Char): Boolean =
        ch in '\u064B'..'\u065F' || ch == '\u0670' || ch in '\u0616'..'\u061A'

    private fun mapPunct(ch: Char): Char = when (ch) {
        ',' -> '،'
        '?' -> '؟'
        ';' -> '؛'
        '…' -> '.'
        '»', '«', '“', '”', '‘', '’', '"', '\'', '`', '(', ')', '[', ']', '{', '}',
        '–', '—', '−', '-', '‐', '/', '\\', '*', '_', '|', '×', '=', '+' -> ' '
        else -> ch
    }

    /**
     * Full port of the author's normalize_fa.py normalize(), in the same order:
     * NFC → strip invisibles + diacritics → fold Arabic letters/digits →
     * spell out digit runs → map Latin punctuation → drop anything outside
     * the allowed set (Latin, emoji, symbols become spaces) → ZWNJ cleanup
     * → collapse whitespace, trim, strip leading punctuation.
     */
    fun normalize(text: String): String {
        var s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        val stripped = StringBuilder(s.length)
        for (ch in s) {
            if (!isInvisible(ch) && !isDiacritic(ch)) stripped.append(ch)
        }
        s = stripped.toString()
        val folded = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                'ي', 'ى', 'ۍ', 'ې' -> folded.append('ی')
                'ك', 'ڪ' -> folded.append('ک')
                'ة', 'ۀ' -> folded.append('ه')
                'أ', 'إ', 'ٱ', 'ٲ', 'ٳ' -> folded.append('ا')
                'ؤ' -> folded.append('و')
                'ۂ', 'ۃ' -> folded.append('ه')
                'ء', 'ـ' -> { /* bare hamza / tatweel: not spoken */ }
                '٬' -> { /* Arabic thousands separator: dropped */ }
                '٫' -> folded.append('.')
                '٪', '%' -> folded.append(" درصد ")
                in '\u06F0'..'\u06F9' -> folded.append('0' + (ch - '\u06F0'))
                in '\u0660'..'\u0669' -> folded.append('0' + (ch - '\u0660'))
                else -> folded.append(ch)
            }
        }
        s = spellNumbers(folded.toString())
        val punct = StringBuilder(s.length)
        for (ch in s) punct.append(mapPunct(ch))
        s = punct.toString()
        val allowed = StringBuilder(s.length)
        for (ch in s) allowed.append(if (isAllowed(ch)) ch else ' ')
        return finish(allowed.toString())
    }

    /** ZWNJ only between two Persian letters; collapse whitespace; trim. */
    private fun finish(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u200C') {
                val prev = out.lastOrNull()
                val next = if (i + 1 < text.length) text[i + 1] else null
                if (prev != null && isPersianLetter(prev) && next != null && isPersianLetter(next)) {
                    out.append(ch)
                }
                i++
                continue
            }
            if (ch.isWhitespace()) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                while (i < text.length && text[i].isWhitespace()) i++
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString().trim().trimStart('.', '،', '؛', '؟', '!', ':')
    }

    private fun spellNumbers(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c !in '0'..'9') {
                out.append(c)
                i++
                continue
            }
            var j = i
            val digits = StringBuilder()
            while (j < text.length) {
                val d = text[j]
                if (d in '0'..'9') {
                    digits.append(d)
                    j++
                } else if (d == ',' && isThousandsSeparator(text, j)) {
                    j++
                } else break
            }
            var frac: String? = null
            if (j < text.length && text[j] == '.' && j + 1 < text.length && text[j + 1] in '0'..'9') {
                j++
                val f = StringBuilder()
                while (j < text.length && text[j] in '0'..'9') {
                    f.append(text[j])
                    j++
                }
                frac = f.toString()
            }
            out.append(' ')
            out.append(numberTokenToWords(digits.toString(), frac))
            out.append(' ')
            i = j
        }
        return out.toString()
    }

    /** A comma is a thousands separator when exactly three digits follow it. */
    private fun isThousandsSeparator(text: String, comma: Int): Boolean {
        var k = comma + 1
        var count = 0
        while (k < text.length && text[k] in '0'..'9' && count < 3) {
            k++
            count++
        }
        return count == 3 && (k >= text.length || text[k] !in '0'..'9')
    }

    private fun numberTokenToWords(whole: String, frac: String?): String {
        if (whole.length > MAX_NUMBER_DIGITS || (whole.startsWith('0') && whole.length > 1)) {
            val w = digitsOneByOne(whole)
            val f = if (frac != null) " " + digitsOneByOne(frac) else ""
            return w + f
        }
        val stripped = whole.trimStart('0').ifEmpty { "0" }
        var words = numberToWords(stripped.toLong())
        if (frac != null) {
            val f = frac.trimEnd('0')
            if (f.isNotEmpty()) {
                val unit = FRACTION_UNITS[f.length]
                words += if (unit != null) " ممیز " + numberToWords(f.toLong()) + " " + unit
                else " ممیز " + digitsOneByOne(f)
            }
        }
        return words
    }

    private fun digitsOneByOne(digits: String): String =
        digits.map { if (it == '0') "صفر" else ONES[it - '0'] }.joinToString(" ")

    private fun numberToWords(n: Long): String {
        if (n == 0L) return "صفر"
        var rest = n
        val parts = mutableListOf<String>()
        for ((value, name) in SCALES) {
            if (rest >= value) {
                val count = rest / value
                rest %= value
                val head = if (count == 1L && value == 1_000L) "" else underThousand(count.toInt()) + " "
                parts.add("$head$name".trim())
            }
        }
        if (rest > 0) parts.add(underThousand(rest.toInt()))
        return parts.joinToString(" و ")
    }

    private fun underThousand(n: Int): String {
        var rest = n
        val parts = mutableListOf<String>()
        if (rest >= 100) {
            parts.add(HUNDREDS[rest / 100])
            rest %= 100
        }
        if (rest >= 20) {
            parts.add(TENS[rest / 10])
            rest %= 10
        }
        if (rest in 10..19) {
            parts.add(TEENS[rest - 10])
            rest = 0
        }
        if (rest > 0) parts.add(ONES[rest])
        return parts.joinToString(" و ")
    }
}
