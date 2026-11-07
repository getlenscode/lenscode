package com.lenscode.app.voice

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

/** An address to open in the app's browser, plus a note for the user when it can't be what they meant. */
data class MachineAddress(val address: String, val warning: String? = null)

private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/**
 * "localhost" means the headset in this app's own browser, but a dev server the user is talking about
 * runs on the machine the agent is on. Points a loopback address at [machineId] when that id is an
 * address the headset can reach (an IPv4 or a .local name); otherwise leaves it and says why.
 * Other hosts, and the "local" pseudo-machine, are untouched.
 */
fun addressForMachine(address: String, machineId: String): MachineAddress {
    val host = address.substringAfter("://").substringBefore('/').substringBefore(':')
    val loopback = host == "localhost" || host == "127.0.0.1"
    if (machineId == "local" || !loopback) return MachineAddress(address)
    val reachable = ipv4.matches(machineId) || machineId.endsWith(".local")
    return if (reachable) {
        MachineAddress(address.replaceFirst(host, machineId))
    } else {
        MachineAddress(address, "\"$host\" is the headset here; the server runs on $machineId.")
    }
}

private val addressParts = Regex("""^(?:([a-z]+)://)?([^/:\s]*)(?::(\d*))?(.*)$""", RegexOption.IGNORE_CASE)
private val schemeWords = setOf("http", "https", "ws", "wss")
private val ordinals = mapOf("first" to 0, "second" to 1, "third" to 2, "fourth" to 3, "last" to 3)
private val looksLikeAddress = Regex("""\d+\.\d+\.\d+\.\d+|localhost|://""")
private val editLeadWords = setOf(
    "add", "make", "change", "set", "use", "actually", "no", "and", "also", "then", "switch", "update",
    "the", "replace",
)
private val numberToken = Regex("""^\d+$""")

/**
 * True when [spoken] is a whole new address (a full IPv4, localhost, or a scheme) or there is nothing to
 * amend, so it replaces [current] and needs no interpretation, and it has no number words that need
 * interpreting. Anything else is a correction (or needs the model).
 */
fun replacesWholeAddress(current: String, spoken: String): Boolean {
    val text = collapseSpelledSchemes(spoken.lowercase().replace(Regex("[,?!]"), " "))
    // "three thousand", "one ninety two": the digit-by-digit parser would leave those words in the
    // address ("…:3thousand"), so they need the model to interpret them.
    if (text.split(Regex("\\s+")).any { it.trim('.', ':') in compoundNumberWords }) return false
    return current.isBlank() || looksLikeAddress.containsMatchIn(normalizeSpokenAddress(text))
}

private val compoundNumberWords = setOf(
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    "hundred", "thousand",
)

/**
 * Applies a spoken correction to the address already in the field ("add port 8443", "http instead of
 * https", "make the third octet 5", "make the last digit 6", "second last digit 4"), or, when the
 * utterance is a whole address, replaces it. [current] is what the field held before listening
 * started, so it can be re-applied to every partial result without compounding.
 *
 * A digit is a digit character of an IPv4 host with the dots skipped, so "last digit" of 192.168.3.12
 * is the 2. An octet is a dot-separated number. An edit that cannot be applied (not an IPv4 host, value
 * out of range) leaves [current] as it was.
 */
fun applySpokenAddressEdit(current: String, spoken: String, paths: Boolean = false): String {
    val text = collapseSpelledSchemes(spoken.lowercase().replace(Regex("[,?!]"), " "))
    val normalized = normalizeSpokenAddress(text)
    if (current.isBlank() || looksLikeAddress.containsMatchIn(normalized)) return normalized
    val parts = addressParts.matchEntire(current.trim()) ?: return normalized
    var (scheme, host, port, rest) = parts.destructured
    val words = text.split(Regex("\\s+")).map { it.trim('.', ':') }.filter { it.isNotEmpty() }
    var edited = false

    // Scheme: the word after "instead of" is the old one; for "X to Y" the new one is Y.
    val schemes = words.filter { it in schemeWords }
    if (schemes.isNotEmpty()) {
        val toIdx = words.indexOf("to")
        scheme = when {
            "instead" in words || "rather" in words -> schemes.first()
            toIdx > 0 && words.getOrNull(toIdx + 1) in schemeWords -> words[toIdx + 1]
            else -> schemes.first()
        }
        edited = true
    }

    val portAt = words.indexOfFirst { it == "port" || it == "colon" }
    if (portAt >= 0) {
        val digits = digitsAfter(words, portAt + 1)
        if (digits.isNotEmpty() && digits.toInt() in 1..65535) {
            port = digits
            edited = true
        }
    }

    val octetAt = words.indexOfFirst { it == "octet" || it == "number" || it == "part" || it == "segment" }
    val digitAt = words.indexOf("digit")
    if (octetAt > 0 && ordinals.containsKey(words[octetAt - 1])) {
        val value = digitsAfter(words, octetAt + 1)
        val octets = host.split('.').toMutableList()
        if (octets.size == 4 && value.isNotEmpty() && value.toInt() in 0..255) {
            octets[ordinals.getValue(words[octetAt - 1])] = value.toInt().toString()
            host = octets.joinToString(".")
        }
        edited = true
    } else if (digitAt > 0) {
        val value = digitsAfter(words, digitAt + 1)
        val fromEnd = when {
            words.getOrNull(digitAt - 1) == "last" && words.getOrNull(digitAt - 2) in setOf("second", "2nd") -> 1
            words.getOrNull(digitAt - 1) == "second" && words.getOrNull(digitAt - 2) == "last" -> 1
            words.getOrNull(digitAt - 1) == "penultimate" -> 1
            words.getOrNull(digitAt - 1) == "last" -> 0
            else -> -1
        }
        if (fromEnd >= 0 && value.length == 1 && ipv4.matches(host)) {
            val idxs = host.indices.filter { host[it].isDigit() }
            val at = idxs[idxs.size - 1 - fromEnd]
            host = host.replaceRange(at, at + 1, value)
        }
        edited = true
    }

    if (paths) {
        // Browser addresses also have a path: "add slash docs to the end", "remove the port", …
        if (removesPort(words)) {
            port = ""
            edited = true
        }
        spokenPathEdit(words, rest)?.let {
            rest = it
            edited = true
        }
    }

    // Still saying the lead-in ("add", "make the…") — don't replace the field with those words.
    if (!edited) return if (words.firstOrNull() in editLeadWords) current else normalized
    val prefix = if (scheme.isEmpty()) "" else "$scheme://"
    return prefix + host + (if (port.isEmpty()) "" else ":$port") + rest
}

private val appendLeads = setOf("add", "append", "attach", "put", "tack")
private val removeLeads = setOf("remove", "clear", "delete", "drop", "reset", "no")
private val replaceLeads = setOf("change", "set", "replace", "use", "switch", "make")
private val endPhrases = listOf(
    listOf("to", "the", "end"), listOf("at", "the", "end"), listOf("on", "the", "end"),
    listOf("to", "the", "url"), listOf("to", "the", "address"), listOf("to", "the", "path"),
    listOf("to", "end"), listOf("at", "end"), listOf("to", "it"),
)
private val segmentWords = setOf("segment", "part", "folder", "level", "directory", "section", "piece")

private fun removesPort(words: List<String>) =
    words.firstOrNull() in removeLeads && "port" in words

/** One spoken path piece: digit words become digits, "dot"/"dash"/"underscore" their symbols, the rest stays as heard. */
private fun segmentToken(w: String): String =
    digitWords[w] ?: symbolWords[w] ?: w

/** "api slash v one" → ["api", "v1"]; the pieces between spoken "slash"es, joined without spaces. */
private fun spokenSegments(words: List<String>): List<String> {
    val segments = mutableListOf<String>()
    var current = StringBuilder()
    var seenSlash = false
    for (w in words) {
        when {
            w == "slash" -> {
                seenSlash = true
                if (current.isNotEmpty()) segments += current.toString()
                current = StringBuilder()
            }
            // Filler before the path starts ("add the slash docs"); inside it every word is part of the path ("a").
            !seenSlash && w in setOf("the", "a", "an", "path", "url", "address") -> Unit
            else -> current.append(segmentToken(w))
        }
    }
    if (current.isNotEmpty()) segments += current.toString()
    return segments
}

/**
 * A spoken change to the path part of an address ([rest] is everything after host and port: path, query,
 * fragment), or null when [words] aren't a path edit.
 *  - "add slash docs [to the end]", "add docs to the end": append segments to the path
 *  - "go to slash admin", "set the path to slash admin dot html": replace the path
 *  - "remove the path": drop it; "remove the last segment" / "go up one level": drop the last piece
 */
private fun spokenPathEdit(words: List<String>, rest: String): String? {
    if (words.isEmpty()) return null
    val tail = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) "" else rest.substring(it) }
    val path = rest.removeSuffix(tail)
    val first = words.first()

    // Remove the path / the last segment.
    if (first in removeLeads && "path" in words && words.none { it in segmentWords }) return ""
    val up = words.size >= 2 && first == "go" && words[1] == "up"
    if ((first in removeLeads && words.any { it in segmentWords } && "last" in words) || up) {
        val kept = path.trimEnd('/').substringBeforeLast('/', "")
        return kept + tail
    }

    // Strip a trailing "to the end"-style phrase; it also marks an append.
    var body = words
    var endMarked = false
    for (phrase in endPhrases) {
        if (body.size > phrase.size && body.takeLast(phrase.size) == phrase) {
            body = body.dropLast(phrase.size)
            endMarked = true
            break
        }
    }

    if (first in appendLeads && (endMarked || "slash" in body)) {
        val segments = spokenSegments(body.drop(1))
        val base = path.trimEnd('/')
        return if (segments.isEmpty()) "$base/$tail" else base + "/" + segments.joinToString("/") + tail
    }

    // "go to slash admin" / "open slash admin" / "set the path to slash admin": a path that starts with "slash".
    val goesToPath = first in setOf("go", "open", "navigate") &&
        (body.getOrNull(1) == "slash" || (body.getOrNull(1) == "to" && body.getOrNull(2) == "slash"))
    val setsPath = "path" in body && "slash" in body && first in replaceLeads
    val replaces = goesToPath || setsPath
    if (replaces) {
        val fromSlash = body.indexOf("slash")
        val segments = spokenSegments(body.drop(fromSlash))
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }
    return null
}

/** The digits spoken from [from] on, skipping filler like "to"/"as"/"is" and stopping at anything else. */
private fun digitsAfter(words: List<String>, from: Int): String {
    val sb = StringBuilder()
    for (w in words.drop(from)) {
        val d = digitWords[w] ?: w.takeIf { numberToken.matches(it) }
        when {
            d != null -> sb.append(d)
            sb.isEmpty() && w in setOf("to", "as", "is", "be", "at", "the", "a", "an", "of", "ip", "address") -> continue
            else -> break
        }
    }
    return sb.toString()
}

/** "w s s" / "h t t p s" as a recognizer sometimes spells them out. */
private fun collapseSpelledSchemes(s: String): String = s
    .replace(Regex("""\bh\s+t\s+t\s+p\s+s\b"""), "https")
    .replace(Regex("""\bh\s+t\s+t\s+p\b"""), "http")
    .replace(Regex("""\bw\s+s\s+s\b"""), "wss")
    .replace(Regex("""\bw\s+s\b"""), "ws")
