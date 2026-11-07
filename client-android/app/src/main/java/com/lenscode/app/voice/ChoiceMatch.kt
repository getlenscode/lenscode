package com.lenscode.app.voice

import com.lenscode.app.net.MachineInfo
import com.lenscode.app.net.ProviderInfo

/** Outcome of matching something the user said against a list the app already knows. */
sealed class ChoiceMatch<out T> {
    data class Found<T>(val value: T) : ChoiceMatch<T>()

    /** Nothing in the list fits. */
    data object None : ChoiceMatch<Nothing>()

    /** Several fit equally well, so guessing would pick the wrong one. */
    data class Ambiguous<T>(val candidates: List<T>) : ChoiceMatch<T>()
}

private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/**
 * Matches a spoken machine name against the registered hosts ("dev a" → `dev-a`, "the mac mini" →
 * `Mac mini`). Exact label or id wins; then a name that starts with it; then one containing it (or
 * contained in it, for filler like "the … machine"). Several equally good hits are [ChoiceMatch.Ambiguous].
 * The built-in "local" pseudo-machine is never offered.
 */
fun matchMachine(heard: String, machines: List<MachineInfo>): ChoiceMatch<MachineInfo> {
    val want = squash(heard.replace(Regex("(?i)\\b(the|machine|host|computer|server|please)\\b"), " "))
    if (want.isEmpty()) return ChoiceMatch.None
    val hosts = machines.filter { it.id != "local" }
    // A hostname's domain ("macmini.local") says nothing about which machine it is, and would let "local" match.
    fun names(m: MachineInfo) = listOf(squash(m.label), squash(m.id.substringBefore('.'))).filter { it.isNotEmpty() }

    val tiers: List<(String) -> Boolean> = listOf(
        { n -> n == want },
        { n -> n.startsWith(want) },
        { n -> n.contains(want) || want.contains(n) },
    )
    for (tier in tiers) {
        val hits = hosts.filter { m -> names(m).any(tier) }
        if (hits.size == 1) return ChoiceMatch.Found(hits.single())
        if (hits.size > 1) return ChoiceMatch.Ambiguous(hits)
    }
    return ChoiceMatch.None
}

fun machineName(m: MachineInfo): String = m.label.ifBlank { m.id }

/** What the popup says when [heard] isn't one of the machines, so the user knows what they can say. */
fun unknownMachineMessage(heard: String, match: ChoiceMatch<MachineInfo>, machines: List<MachineInfo>): String {
    val hosts = machines.filter { it.id != "local" }
    return when {
        match is ChoiceMatch.Ambiguous ->
            "\"$heard\" matches more than one machine: ${match.candidates.joinToString { machineName(it) }}. Say the full name."
        hosts.isEmpty() ->
            "\"$heard\" isn't in your connected machines — none are registered. Add a host to ~/.ssh/config on the bridge computer."
        else ->
            "\"$heard\" isn't in your connected machines. Available: ${hosts.joinToString { machineName(it) }}."
    }
}

/** What the popup says when [heard] names no available agent (claude, codex, pi…) or model. */
fun unknownAgentModelMessage(heard: String, providers: List<ProviderInfo>): String {
    val available = providers.filter { it.available }
    if (available.isEmpty()) return "\"$heard\" can't be selected — no agents are available from the bridge right now."
    val list = available.joinToString("; ") { p ->
        val models = p.models.take(4).joinToString(", ")
        if (models.isEmpty()) p.provider else "${p.provider} ($models${if (p.models.size > 4) ", …" else ""})"
    }
    return "No available agent or model matches \"$heard\". Available: $list."
}

/** An agent (provider) with, optionally, one of its models: what `resolveModel` produces and `chooseModel` applies. */
data class AgentModelChoice(val provider: String, val model: String?) {
    val label: String get() = if (model == null) provider else "$provider · $model"
}

/** 1.0 for identical, falling toward 0 as more single-character edits are needed (Levenshtein over the longer length). */
internal fun similarity(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
}

private const val MIN_SIMILARITY = 0.6

/**
 * The agent or model closest to what was said, for "did you mean…?" when nothing matches exactly. Compares
 * the squashed words against each available agent's name and each model id, and also against the pieces of
 * a model id ("claude-sonnet-5-5" → "sonnet", so "sonet" finds it). Speech-to-text errors are mostly a
 * letter or two ("codecs" for "codex"), which is what the edit-distance score tolerates. Null when nothing
 * is close enough to be worth asking about. Several equally close models resolve to the shortest id, as
 * `resolveModel` does for a family name.
 */
fun closestAgentModel(heard: String, providers: List<ProviderInfo>): AgentModelChoice? {
    val want = squash(heard)
    if (want.isEmpty()) return null
    var best: AgentModelChoice? = null
    var bestScore = 0.0
    fun consider(choice: AgentModelChoice, names: List<String>) {
        val score = names.maxOf { similarity(want, it) }
        val better = score > bestScore ||
            (score == bestScore && best != null && (choice.model?.length ?: 0) < (best?.model?.length ?: 0))
        if (score >= MIN_SIMILARITY && better) {
            best = choice
            bestScore = score
        }
    }
    for (p in providers.filter { it.available }) {
        consider(AgentModelChoice(p.provider, null), listOf(squash(p.provider)))
        for (m in p.models) {
            val pieces = m.split('-', '_', '.', ':', '/').map(::squash).filter { it.length >= 3 }
            consider(AgentModelChoice(p.provider, m), listOf(squash(m)) + pieces)
        }
    }
    return best
}

/**
 * The agent or model a spoken name refers to, when it clearly does: an agent's own name, or any available
 * model whose id contains it ("opus" → the Opus model). Several versions of one family match, so the
 * shortest id wins (the base/latest alias). Speech rarely matches an id exactly, hence the contains.
 */
fun resolveAgentModel(heard: String, providers: List<ProviderInfo>): AgentModelChoice? {
    val want = squash(heard)
    if (want.isEmpty()) return null
    val available = providers.filter { it.available }
    available.firstOrNull { squash(it.provider) == want || (it.provider == "omp" && want == "ohmypi") }
        ?.let { return AgentModelChoice(it.provider, null) }
    return available
        .flatMap { p -> p.models.map { AgentModelChoice(p.provider, it) } }
        .filter { squash(it.model.orEmpty()).contains(want) }
        .minByOrNull { it.model.orEmpty().length }
}

/** What to do with a spoken agent/model name. */
sealed class AgentModelDecision {
    /** It clearly names this one: apply it. */
    data class Use(val choice: AgentModelChoice) : AgentModelDecision()

    /** Nothing matches, but this is close: ask "did you mean…?" and apply it only after a yes. */
    data class Suggest(val closest: AgentModelChoice) : AgentModelDecision()

    /** Nothing matches or is close: tell the user what exists. */
    data class Reject(val message: String) : AgentModelDecision()
}

fun decideAgentModel(heard: String, providers: List<ProviderInfo>): AgentModelDecision {
    resolveAgentModel(heard, providers)?.let { return AgentModelDecision.Use(it) }
    closestAgentModel(heard, providers)?.let { return AgentModelDecision.Suggest(it) }
    return AgentModelDecision.Reject(unknownAgentModelMessage(heard, providers))
}
