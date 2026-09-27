package com.lenscode.app.voice

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
    /** Interrupts (Ctrl+C) the currently selected terminal's process — the pane itself stays open. */
    object KillTerminal : VoiceCommand()
    /** Closes/removes terminal pane(s) entirely. target: "current", "all", or a name/cwd to match. */
    data class CloseTerminal(val target: String = "current") : VoiceCommand()
    /** target: "current" (whichever agent card is open), "all", or a name/label to match. */
    data class ArchiveAgent(val target: String = "current") : VoiceCommand()
    /** Opens a specific agent's detail card by name/label — distinct from Navigate("agents"), the plain list. */
    data class OpenAgent(val name: String) : VoiceCommand()
    /** Closes/cancels whatever confirmation card or overlay is currently open. */
    object Dismiss : VoiceCommand()
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
    "browser" to listOf("browser", "web browser"),
    "settings" to listOf("settings", "preferences", "options"),
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

    val openAgentName = extractOpenAgentName(text)

    return when {
        Regex("\\b(discard|reject|undo|revert)\\b.*\\bchanges?\\b").containsMatchIn(text) ->
            VoiceCommand.DiscardChanges
        Regex("\\b(accept|approve|apply|keep|stage)\\b.*\\bchanges?\\b").containsMatchIn(text) ->
            VoiceCommand.AcceptChanges
        // Checked before KillTerminal below: an agent can have "terminal" in its
        // own name/title (e.g. "the terminal test agent") — the plain
        // "kill...terminal" pattern would otherwise misfire on that as a pane kill.
        Regex("\\b(archive|close|stop|kill)\\b.*\\bagents?\\b").containsMatchIn(text) ->
            VoiceCommand.ArchiveAgent(extractTarget(text, "agents?"))
        // Only the unambiguous "agent named/called X" phrasing — anything looser
        // ("open the frontend agent") is left to the Gemini router; a bare "open
        // agent" with no name still falls through to plain Navigate below.
        openAgentName != null -> VoiceCommand.OpenAgent(openAgentName)
        // Checked before kill_terminal: "close" means remove the pane entirely
        // (distinct action) — only kill/stop/interrupt leaves the pane open.
        Regex("\\bclose\\b.*\\bterminals?\\b").containsMatchIn(text) ->
            VoiceCommand.CloseTerminal(extractTarget(text, "terminals?"))
        Regex("\\b(kill|stop)\\b.*\\b(server|terminal|process)\\b").containsMatchIn(text) ->
            VoiceCommand.KillTerminal
        Regex("\\b(new|create|start)\\b.*\\bterminal\\b").containsMatchIn(text) ->
            VoiceCommand.NewTerminal
        Regex("\\b(cancel|dismiss|nevermind|never mind|go back)\\b").containsMatchIn(text) ||
            text.contains("close this") ->
            VoiceCommand.Dismiss
        else -> matchNavigation(text)?.let { VoiceCommand.Navigate(it) } ?: VoiceCommand.Search(rawText.trim())
    }
}

/**
 * Only the unambiguous "agent(s) named/called X" phrasing, gated on an
 * open-ish verb being present — anything looser ("open the frontend agent")
 * is left to the Gemini router (KoogVoiceRouter); this is just the no-key
 * degraded path, so it stays conservative to avoid misreading a plain
 * "open agents" as naming something.
 */
private fun extractOpenAgentName(text: String): String? {
    if (!Regex("\\b(open|show|select|view)\\b").containsMatchIn(text)) return null
    return Regex("\\bagents?\\b\\s+(?:named|called)\\s+(.+)").find(text)
        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}

private val CLOSE_VERBS = listOf("archive", "close", "stop", "kill")

/**
 * Best-effort target extraction for the offline/no-key fallback router,
 * shared by archive_agent and close_terminal — "all" for every match,
 * otherwise whatever name sits before/after the [subjectPattern] word
 * (e.g. "kill the terminal test agent" → "terminal test", "archive agent B"
 * → "B", "close all my open terminals" → "all"), or "current" when nothing
 * more specific is said. The Gemini router (KoogVoiceRouter) does this
 * extraction properly when a key is configured — this is only the no-key
 * degraded path.
 */
private fun extractTarget(text: String, subjectPattern: String): String {
    if (Regex("\\ball\\b").containsMatchIn(text)) return "all"
    // Name after the subject: "archive agent B", "kill the agent named foo",
    // "stop agent called bar" — tried first since it's unambiguous when present.
    Regex("\\b$subjectPattern\\b\\s+(?:named|called)?\\s*(.+)").find(text)?.groupValues?.get(1)
        ?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    // Name before the subject: "kill the terminal test agent" → words before
    // "agent" minus the leading verb ("kill") and article/demonstrative
    // ("the"/"this"/"that", which mean "current", not part of a name).
    Regex("(.+?)\\s+$subjectPattern\\b").find(text)?.groupValues?.get(1)?.let { prefix ->
        val words = prefix.trim().split(Regex("\\s+")).toMutableList()
        val leadingFiller = CLOSE_VERBS + listOf("the", "this", "that", "my", "open", "all")
        while (words.isNotEmpty() && words.first() in leadingFiller) {
            words.removeAt(0)
        }
        words.joinToString(" ").trim().takeIf { it.isNotBlank() }?.let { return it }
    }
    return "current"
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
