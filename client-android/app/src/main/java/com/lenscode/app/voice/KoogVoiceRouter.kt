package com.lenscode.app.voice

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.structure.executeStructured
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agentic voice command grammar (docs/paseo-migration-plan.md follow-up).
 * Gemini (via Koog, entirely on-device) returns these directly as structured
 * output — no manual keyword matching. A [VoiceCommandPlan] carries a
 * *sequence* of actions so a compound utterance ("navigate to code and open
 * line 20 in package.json") resolves to multiple steps, unlike the old
 * deterministic [VoiceCommandRouter], which could only ever match one intent
 * and silently dropped the rest of the sentence.
 */
@Serializable
sealed class VoiceAction {
    @Serializable
    @SerialName("navigate")
    @LLMDescription("Switch the active view.")
    data class Navigate(
        @property:LLMDescription(
            "one of: code, diff, terminal, git, agents, permissions, remote, browser, settings, home",
        )
        val view: String,
    ) : VoiceAction()

    @Serializable
    @SerialName("open_file")
    @LLMDescription(
        "Open a specific file by name/path in the Code view. Use this instead of " +
            "'search' whenever a filename is clearly named (e.g. \"open package.json\", " +
            "\"open the auth middleware file\").",
    )
    data class OpenFile(
        @property:LLMDescription("the file's name or path, as heard — may be partial or approximate")
        val path: String,
        @property:LLMDescription("1-based line number to jump to, if one was mentioned; null otherwise")
        val line: Int? = null,
    ) : VoiceAction()

    @Serializable
    @SerialName("accept_changes")
    @LLMDescription("Stage/accept all changes in the diff file currently open in the Diff view.")
    data object AcceptChanges : VoiceAction()

    @Serializable
    @SerialName("discard_changes")
    @LLMDescription(
        "Discard all changes in the diff file currently open in the Diff view. " +
            "Destructive — the app always confirms with the user before actually discarding.",
    )
    data object DiscardChanges : VoiceAction()

    @Serializable
    @SerialName("new_terminal")
    @LLMDescription("Create a new terminal.")
    data object NewTerminal : VoiceAction()

    @Serializable
    @SerialName("kill_terminal")
    @LLMDescription(
        "Interrupt (Ctrl+C) the process running in the currently selected terminal — the " +
            "terminal itself stays open afterward. Destructive — the app always confirms " +
            "with the user first. Use close_terminal instead when the intent is to get rid " +
            "of the terminal pane itself (\"close it\", \"close all my terminals\").",
    )
    data object KillTerminal : VoiceAction()

    @Serializable
    @SerialName("close_terminal")
    @LLMDescription(
        "Close/remove one or more terminal panes entirely (not just interrupt what's " +
            "running in them — the pane itself goes away). Not destructive enough to need " +
            "a confirm card (matches the existing tap-to-close button, which doesn't confirm " +
            "either).",
    )
    data class CloseTerminal(
        @property:LLMDescription(
            "\"current\" (default) for whichever terminal is currently selected, \"all\" for " +
                "every open terminal (\"close all my terminals\"), or a terminal's name/cwd as heard.",
        )
        val target: String = "current",
    ) : VoiceAction()

    @Serializable
    @SerialName("archive_agent")
    @LLMDescription(
        "Archive (kill/stop/close) one or more agents. Destructive — the app always " +
            "confirms with the user first (one confirm card, or one 'archive all N' card " +
            "for target=all).",
    )
    data class ArchiveAgent(
        @property:LLMDescription(
            "\"current\" when no specific agent is named (archives whichever agent's " +
                "detail card is currently open) — the default. \"all\" for \"kill all agents\"/" +
                "\"stop every agent\". Otherwise the agent's name/label as heard (e.g. \"the " +
                "terminal test agent\", \"agent B\") to archive that one specifically.",
        )
        val target: String = "current",
    ) : VoiceAction()

    @Serializable
    @SerialName("search")
    @LLMDescription(
        "Fall back to a plain file/text search. Use this only when no other action " +
            "clearly applies — for example a vague description of file contents rather " +
            "than a specific filename or command.",
    )
    data class Search(@property:LLMDescription("the search query") val query: String) : VoiceAction()

    @Serializable
    @SerialName("new_agent")
    @LLMDescription(
        "Open/start/spawn a new coding agent with no task yet — e.g. \"open a cloud agent\", " +
            "\"start a new agent\", \"spawn an agent\". Use this instead of navigate when the " +
            "transcript is about creating an agent, not just looking at the agents list. Use " +
            "ask_agent instead whenever the transcript also names actual work to do.",
    )
    data object NewAgent : VoiceAction()

    @Serializable
    @SerialName("ask_agent")
    @LLMDescription(
        "Hand a real coding/dev task off to the coding agent (Claude or Codex) to actually " +
            "perform — e.g. \"start a http server in this project\", \"add a test for the " +
            "login route\", \"fix the bug in auth.ts\". Use this whenever the transcript " +
            "describes work to be *done*, not just something to navigate to, find, or a " +
            "fixed app action from the rest of this grammar.",
    )
    data class AskAgent(
        @property:LLMDescription("the task/instruction, as heard, to hand to the coding agent")
        val instruction: String,
    ) : VoiceAction()

    @Serializable
    @SerialName("open_agent")
    @LLMDescription(
        "Open a specific agent's detail card by name/label, as heard — e.g. \"show me the " +
            "terminal test agent\", \"open agent B\". Different from navigate(agents), which " +
            "only shows the agents list, not one specific agent's card.",
    )
    data class OpenAgent(
        @property:LLMDescription("the agent's name/label to open, as heard")
        val name: String,
    ) : VoiceAction()

    @Serializable
    @SerialName("dismiss")
    @LLMDescription(
        "Close/cancel whatever confirmation card, agent detail card, or dialog overlay is " +
            "currently open — \"cancel\", \"never mind\", \"close this\", \"go back\", " +
            "\"dismiss\". Not for navigating between main views (use navigate for that).",
    )
    data object Dismiss : VoiceAction()

    @Serializable
    @SerialName("respond_permission")
    @LLMDescription(
        "Answer an agent's pending tool-call permission request (\"allow it\", \"approve\", " +
            "\"deny\", \"reject\", \"no\"). Only valid when the context says a permission is " +
            "actually pending — never guess this action otherwise.",
    )
    data class RespondPermission(
        @property:LLMDescription("\"allow\" or \"deny\", from what was heard")
        val behavior: String,
    ) : VoiceAction()
}

/**
 * The wire shape Gemini actually fills in — flat, with every action's fields
 * laid out as optionals on one type, rather than [VoiceAction]'s sealed
 * hierarchy. Confirmed live (2026-09-06): a `oneOf`-style discriminated
 * union (which is what a Kotlin sealed class compiles to in the generated
 * JSON schema) is unreliable for Gemini's structured output — the model
 * kept collapsing onto a nearby parameterless variant instead of
 * constructing one with real extracted field values, even with matching
 * few-shot examples. A flat schema with no `oneOf` fixed it outright. See
 * [toVoiceAction] for the conversion into the typed grammar the app executes.
 */
@Serializable
@LLMDescription(
    "One routed action. Fill only the fields relevant to `type`; leave the rest null.",
)
data class VoiceStep(
    @property:LLMDescription(
        "one of: navigate, open_file, accept_changes, discard_changes, new_terminal, " +
            "kill_terminal, close_terminal, archive_agent, open_agent, dismiss, search, " +
            "new_agent, ask_agent, respond_permission",
    )
    val type: String,
    @property:LLMDescription(
        "required when type=navigate: one of code, diff, terminal, git, agents, " +
            "permissions, remote, home. Null otherwise.",
    )
    val view: String? = null,
    @property:LLMDescription("required when type=open_file: the file's name/path, as heard. Null otherwise.")
    val path: String? = null,
    @property:LLMDescription("optional, only for type=open_file: 1-based line number if one was mentioned. Null otherwise.")
    val line: Int? = null,
    @property:LLMDescription("required when type=search: the search query. Null otherwise.")
    val query: String? = null,
    @property:LLMDescription("required when type=ask_agent: the task/instruction, as heard. Null otherwise.")
    val instruction: String? = null,
    @property:LLMDescription("required when type=respond_permission: \"allow\" or \"deny\". Null otherwise.")
    val behavior: String? = null,
    @property:LLMDescription(
        "for type=archive_agent: \"current\" (default), \"all\", or the named agent to " +
            "archive, as heard (null means \"current\"). For type=open_agent: the agent's " +
            "name/label to open, as heard (required). For type=close_terminal: \"current\" " +
            "(default), \"all\", or a terminal's name/cwd as heard. Null otherwise.",
    )
    val target: String? = null,
) {
    fun toVoiceAction(): VoiceAction? = when (type) {
        "navigate" -> view?.let { VoiceAction.Navigate(it) }
        "open_file" -> path?.let { VoiceAction.OpenFile(it, line) }
        "accept_changes" -> VoiceAction.AcceptChanges
        "discard_changes" -> VoiceAction.DiscardChanges
        "new_terminal" -> VoiceAction.NewTerminal
        "kill_terminal" -> VoiceAction.KillTerminal
        "close_terminal" -> VoiceAction.CloseTerminal(target ?: "current")
        "archive_agent" -> VoiceAction.ArchiveAgent(target ?: "current")
        "open_agent" -> target?.takeIf { it.isNotBlank() }?.let { VoiceAction.OpenAgent(it) }
        "dismiss" -> VoiceAction.Dismiss
        "new_agent" -> VoiceAction.NewAgent
        "search" -> query?.let { VoiceAction.Search(it) }
        "ask_agent" -> instruction?.let { VoiceAction.AskAgent(it) }
        "respond_permission" -> behavior?.let { VoiceAction.RespondPermission(it) }
        else -> null
    }
}

@Serializable
@LLMDescription(
    "The ordered list of actions to perform for this voice command. Almost always one " +
        "action; more than one only for a genuinely compound instruction (\"do X and then Y\").",
)
data class VoiceCommandPlan(val actions: List<VoiceStep>) {
    /** Drops any step the model left malformed (e.g. type=navigate with no view) rather than crash on it. */
    fun toActions(): List<VoiceAction> = actions.mapNotNull { it.toVoiceAction() }
}

/** What the router tells the model about what's currently on screen, so it can judge which actions are even valid right now. */
data class VoiceRouterContext(
    val activeView: String,
    val diffFileOpen: String?,
    val terminalSelected: Boolean,
    val agentCardOpen: String?,
    /** True when at least one agent has a tool call waiting on you — makes respond_permission a valid interpretation. */
    val hasPendingPermission: Boolean = false,
)

private const val SYSTEM_PROMPT = """
You are the voice command router for LensCode, a spatial (XR glasses) coding agent workspace.
The user spoke a command; you receive its transcript and must return a VoiceCommandPlan —
an ordered list of actions from the fixed grammar. Rules:
- Prefer exactly one action. Only return more than one for a clearly compound instruction.
- Never invent an action outside the given grammar.
- If the transcript describes actual work to be done in the project — writing code,
  starting a server, adding a test, fixing a bug, refactoring, running something,
  anything a coding agent would need to act on rather than just a UI action — use
  "ask_agent" with the instruction, not "search". "search" is for finding something
  that already exists; "ask_agent" is for making something happen.
- If the transcript is about creating/opening/starting/spawning an agent but names no
  actual task ("open a cloud agent", "start a new agent", "spawn an agent"), use
  "new_agent" — not "navigate" to the agents view. "navigate" is for just looking at
  a view; "new_agent" actually creates the agent. If a task is also named, use
  "ask_agent" instead (it creates the agent too, with that task as its first message).
- Only if the transcript doesn't match any specific action AND isn't a task for the
  coding agent, use "search" with the transcript (or the relevant part of it) as the
  query — this is the last-resort fallback, not the default for anything ambiguous.
- Destructive actions (discard_changes, kill_terminal, archive_agent) are fine to return;
  the app itself always shows the user a confirmation card before actually doing anything
  destructive, so you don't need to hedge or ask for confirmation yourself. close_terminal
  is the one exception — it acts immediately, same as the existing tap-to-close button.
- archive_agent's target: use "all" for "kill/stop/archive all agents" or "every agent".
  Use the agent's name/label as heard when one is named ("kill the terminal test agent",
  "archive agent B", "stop the one called Snip test agent A"). Use "current" (or omit
  target) only when no agent is named and the intent is "archive whatever I'm looking at".
- open_agent is for *looking at* one specific named agent's card ("show me the terminal
  test agent", "open agent B") — not for closing/killing it (that's archive_agent) and not
  for the agents list in general (that's navigate with view=agents).
- close_terminal vs kill_terminal: "close" (or "close all my terminals", "get rid of this
  terminal") means the pane itself should go away — use close_terminal. "kill"/"stop"/
  "interrupt" a running process while keeping the terminal open ("kill the server", "stop
  what's running") means kill_terminal instead. close_terminal's target follows the same
  "all" / named / "current" pattern as archive_agent's.
- Use dismiss whenever the transcript is clearly about closing/canceling whatever's
  currently on screen rather than navigating somewhere or doing a new action — "cancel",
  "never mind", "close this", "go back", a bare "no" with nothing else implied by context.
  Don't use dismiss for "no" when respond_permission's deny is what's actually meant
  (context says a permission is pending) — that takes priority.
- Speech-to-text transcripts can be rough (dropped words, filenames spoken oddly, e.g.
  "package json" for "package.json") — use judgment.
- Only use respond_permission when the context line says a permission is pending. A bare
  "yes"/"no"/"allow it"/"deny" with nothing pending almost never fits anything else either —
  fall back to search with the raw transcript rather than inventing an action.
"""

/**
 * Wraps a single Gemini call (via Koog's structured-output executor) that
 * classifies a voice transcript into a [VoiceCommandPlan]. One instance per
 * app process — cheap to hold, the underlying client is just an HTTP client.
 */
class KoogVoiceRouter(apiKey: String, modelId: String) {
    // See the build.gradle.kts comment on the ktor-client-okhttp dependency —
    // Koog's default Ktor engine crashes on Android once a custom
    // network_security_config.xml is present in the app, regardless of host.
    private val executor = SingleLLMPromptExecutor(GoogleLLMClient(apiKey, GoogleClientSettings(), HttpClient(OkHttp)))

    // Cloning a known-good Gemini model's capabilities/limits rather than
    // hand-picking a capability list: GoogleLLMClient does its own
    // client-side capability gate before calling out (confirmed live —
    // a hand-picked {Temperature, Tools, Schema.JSON.Standard} list threw
    // "Model gemini-3.8-flash does not support chat completions"), and
    // Gemini2_5Flash's real capability set is Koog's own source of truth
    // for what a working Gemini chat model needs, not something to re-guess.
    private val model = GoogleModels.Gemini2_5Flash.copy(id = modelId)

    suspend fun route(transcript: String, context: VoiceRouterContext): Result<VoiceCommandPlan> {
        val contextLine = "Current state: view=${context.activeView}, " +
            "diffFileOpen=${context.diffFileOpen ?: "none"}, " +
            "terminalSelected=${context.terminalSelected}, " +
            "agentCardOpen=${context.agentCardOpen ?: "none"}, " +
            "hasPendingPermission=${context.hasPendingPermission}."
        val result = executor.executeStructured<VoiceCommandPlan>(
            prompt = prompt("voice-command") {
                system(SYSTEM_PROMPT)
                user("$contextLine\n\nTranscript: \"$transcript\"")
            },
            model = model,
            examples = FEW_SHOT_EXAMPLES,
        )
        return result.map { it.structure }
    }

    private companion object {
        val FEW_SHOT_EXAMPLES = listOf(
            VoiceCommandPlan(
                listOf(
                    VoiceStep(type = "navigate", view = "code"),
                    VoiceStep(type = "open_file", path = "package.json", line = 20),
                ),
            ),
            VoiceCommandPlan(listOf(VoiceStep(type = "search", query = "authentication middleware"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "navigate", view = "diff"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "accept_changes"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "open_file", path = "auth.ts"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "new_agent"))),
            VoiceCommandPlan(
                listOf(VoiceStep(type = "ask_agent", instruction = "start a http server in this project")),
            ),
            VoiceCommandPlan(listOf(VoiceStep(type = "ask_agent", instruction = "add a test for the login route"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "respond_permission", behavior = "allow"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "respond_permission", behavior = "deny"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "archive_agent", target = "current"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "archive_agent", target = "all"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "archive_agent", target = "terminal test agent"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "open_agent", target = "terminal test agent"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "dismiss"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "close_terminal", target = "all"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "kill_terminal"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "navigate", view = "settings"))),
            VoiceCommandPlan(listOf(VoiceStep(type = "navigate", view = "browser"))),
        )
    }
}
