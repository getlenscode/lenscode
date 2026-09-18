package dev.aura.xr.voice

/**
 * Normalizes a dictated IP/URL ("one nine two dot one six eight dot one dot
 * one colon three zero zero zero") into the compact string it was meant to be
 * ("192.168.1.1:3000"). Android's SpeechRecognizer transcribes free-form
 * speech, not addresses — spoken digit-by-digit (the natural way to dictate
 * an IP precisely) it comes back as separate number words with no punctuation
 * collapsed, so this fills that gap rather than relying on the recognizer
 * itself to know what an address is.
 */
private val digitWords = mapOf(
    "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "to" to "2", "too" to "2",
    "three" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6",
    "seven" to "7", "eight" to "8", "ate" to "8", "nine" to "9",
)

private val symbolWords = mapOf(
    "dot" to ".", "point" to ".", "colon" to ":", "slash" to "/",
    "dash" to "-", "hyphen" to "-", "underscore" to "_",
)

fun normalizeSpokenAddress(raw: String): String {
    val tokens = raw.trim().split(Regex("\\s+"))
    val sb = StringBuilder()
    for (token in tokens) {
        val clean = token.trim('.', ',', '?', '!').lowercase()
        when {
            clean.isEmpty() -> continue
            digitWords.containsKey(clean) -> sb.append(digitWords.getValue(clean))
            symbolWords.containsKey(clean) -> sb.append(symbolWords.getValue(clean))
            else -> sb.append(token) // not a recognized number/symbol word — keep as-is (e.g. "localhost", "www")
        }
    }
    return sb.toString()
}
