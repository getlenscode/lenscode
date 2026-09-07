package dev.aura.xr.voice

/**
 * What a transcribed voice command resolves to. [parseVoiceCommand] is a
 * plain deterministic keyword router — no NLU/LLM involved — so its
 * behavior is predictable and testable independent of the recognizer.
 * Anything that doesn't match a known phrasing falls back to [Search], the
 * same voice-file-search flow the dock mic used before this router existed.
 */
sealed class VoiceCommand {
    data class Navigate(val view: String) : VoiceCommand()
    object AcceptChanges : VoiceCommand()
    object DiscardChanges : VoiceCommand()
    object NewTerminal : VoiceCommand()
    object KillTerminal : VoiceCommand()
    object ArchiveAgent : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
}

private val VIEW_SYNONYMS: List<Pair<String, List<String>>> = listOf(
    "code" to listOf("code", "files", "file", "editor"),
    "diff" to listOf("diff", "changes", "review"),
    "terminal" to listOf("terminal", "terminals", "shell", "console"),
    "git" to listOf("git", "branch", "branches", "worktree", "worktrees"),
    "agents" to listOf("agent", "agents"),
    "permissions" to listOf("permission", "permissions", "approvals", "approval"),
    "remote" to listOf("remote", "machines", "machine"),
    "home" to listOf("home", "dashboard", "main"),
)

private val NAV_VERBS = listOf(
    "open", "show", "go to", "goto", "navigate to", "navigate", "switch to", "click", "view", "display",
)

/**
 * Routes one final transcript to a [VoiceCommand]. Destructive commands
 * (discard, kill terminal, archive agent) are resolved here but MUST still
 * go through the app's existing confirmation-card pattern before acting
 * (spec §57) — this function only classifies intent, it never executes
 * anything itself.
 */
fun parseVoiceCommand(rawText: String): VoiceCommand {
    val text = rawText.trim().lowercase()
    if (text.isEmpty()) return VoiceCommand.Search(rawText.trim())

    return when {
        Regex("\\b(discard|reject|undo|revert)\\b.*\\bchanges?\\b").containsMatchIn(text) ->
            VoiceCommand.DiscardChanges
        Regex("\\b(accept|approve|apply|keep|stage)\\b.*\\bchanges?\\b").containsMatchIn(text) ->
            VoiceCommand.AcceptChanges
        Regex("\\b(kill|stop)\\b.*\\b(server|terminal|process)\\b").containsMatchIn(text) ->
            VoiceCommand.KillTerminal
        Regex("\\b(new|create|start)\\b.*\\bterminal\\b").containsMatchIn(text) ->
            VoiceCommand.NewTerminal
        Regex("\\b(archive|close|stop)\\b.*\\bagent\\b").containsMatchIn(text) ->
            VoiceCommand.ArchiveAgent
        else -> matchNavigation(text)?.let { VoiceCommand.Navigate(it) } ?: VoiceCommand.Search(rawText.trim())
    }
}

/**
 * A bare view name alone ("diff") or a nav verb plus a synonym ("go to the
 * diff view", "view changes", "click code") both count. A synonym without a
 * verb and without being the *entire* utterance does not — that's almost
 * certainly a file-search query that happens to contain a word like "code"
 * (e.g. "the auth code file").
 */
private fun matchNavigation(text: String): String? {
    for ((view, keywords) in VIEW_SYNONYMS) {
        for (kw in keywords) {
            if (!Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(text)) continue
            if (text == kw || NAV_VERBS.any { text.contains(it) }) return view
        }
    }
    return null
}
