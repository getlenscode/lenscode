package com.lenscode.app.voice

import com.lenscode.app.net.TaskCard
import com.lenscode.app.net.TaskPreset

/** What a spoken task reference matched on the loaded board. */
sealed class TaskMatch {
    data class Found(val task: TaskCard) : TaskMatch()
    data class Ambiguous(val candidates: List<TaskCard>) : TaskMatch()
    data object NotFound : TaskMatch()
}

private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/** "in progress", "review", "cancelled"… -> the wire status, or null when it isn't one. */
fun parseTaskStatus(spoken: String): String? = when (words(spoken).joinToString(" ")) {
    "backlog" -> "backlog"
    "todo", "to do", "ready" -> "todo"
    "in progress", "doing", "started", "working", "in flight" -> "in_progress"
    "in review", "review", "reviewing" -> "in_review"
    "done", "complete", "completed", "finished" -> "done"
    "canceled", "cancelled", "dropped", "wont do", "won t do" -> "canceled"
    else -> null
}

fun parseTaskPriority(spoken: String): String? = when (words(spoken).joinToString(" ")) {
    "urgent", "critical" -> "urgent"
    "high", "important" -> "high"
    "medium", "normal" -> "medium"
    "low", "minor" -> "low"
    "none", "no priority" -> "none"
    else -> null
}

private val ONES = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
)
private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

/** Speech-to-text sometimes spells numbers out: "twelve" -> "12", "twenty one" -> "21". Other words pass through. */
internal fun spokenNumbersToDigits(tokens: List<String>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        val ten = TENS.indexOf(t).takeIf { it >= 2 }
        val one = ONES.indexOf(tokens.getOrNull(i + 1) ?: "").takeIf { it in 1..9 }
        when {
            ten != null && one != null -> { out += (ten * 10 + one).toString(); i += 2 }
            ten != null -> { out += (ten * 10).toString(); i++ }
            t in ONES -> { out += ONES.indexOf(t).toString(); i++ }
            else -> { out += t; i++ }
        }
    }
    return out
}

private val FILLER = setOf("the", "task", "ticket", "card", "issue", "number", "called", "named")

/**
 * Finds the task a person meant. Speech is rough, so in order: the key ("ABC 12", "abc dash 12"),
 * a bare number when that is unique, then every remaining word appearing in a title.
 */
fun resolveTask(ref: String, tasks: List<TaskCard>): TaskMatch {
    val tokens = spokenNumbersToDigits(words(ref).filter { it !in FILLER })
    if (tokens.isEmpty()) return TaskMatch.NotFound

    val compact = tokens.filter { it != "dash" && it != "hyphen" }.joinToString("")
    tasks.firstOrNull { it.key.replace("-", "").lowercase() == compact }?.let { return TaskMatch.Found(it) }

    if (compact.all { it.isDigit() }) {
        val byNumber = tasks.filter { it.key.substringAfter('-') == compact.trimStart('0').ifEmpty { "0" } }
        if (byNumber.size == 1) return TaskMatch.Found(byNumber[0])
        if (byNumber.size > 1) return TaskMatch.Ambiguous(byNumber)
    }

    val byTitle = tasks.filter { t ->
        val title = t.title.lowercase()
        tokens.all { it in title }
    }
    return when (byTitle.size) {
        0 -> TaskMatch.NotFound
        1 -> TaskMatch.Found(byTitle[0])
        else -> TaskMatch.Ambiguous(byTitle)
    }
}

/** "claude", "codex"… -> the plugin preset for that agent: by name first, then by provider id. */
fun resolvePreset(spoken: String, presets: List<TaskPreset>): TaskPreset? {
    val s = spoken.lowercase().trim()
    if (s.isEmpty()) return null
    return presets.firstOrNull { it.name.lowercase() == s }
        ?: presets.firstOrNull { it.providerId.lowercase() == s }
        ?: presets.firstOrNull { it.name.lowercase().contains(s) || it.providerId.lowercase().contains(s) }
}

/**
 * The task the router named. A key the model picked from the board wins, since it was chosen with the whole
 * list in view; otherwise the words are matched here, and the model's alternatives are offered when nothing matches.
 */
fun resolveTaskChoice(ref: String, key: String?, alternatives: List<String>, tasks: List<TaskCard>): TaskMatch {
    key?.let { k -> tasks.firstOrNull { it.key.equals(k, ignoreCase = true) } }?.let { return TaskMatch.Found(it) }
    val matched = resolveTask(ref, tasks)
    if (matched !is TaskMatch.NotFound) return matched
    val offered = alternatives.mapNotNull { a -> tasks.firstOrNull { it.key.equals(a, ignoreCase = true) } }
    return if (offered.isEmpty()) TaskMatch.NotFound else TaskMatch.Ambiguous(offered)
}
