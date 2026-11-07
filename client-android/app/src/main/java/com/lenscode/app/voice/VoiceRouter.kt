package com.lenscode.app.voice

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agentic voice command grammar (docs/paseo-migration-plan.md follow-up).
 * An OpenRouter model returns these as JSON — no manual keyword matching. A [VoiceCommandPlan] carries a
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
        "Hand a real coding/dev task off to the coding agent (Claude, Codex, Pi, or Oh My Pi) to actually " +
            "perform — e.g. \"start a http server in this project\", \"add a test for the " +
            "login route\", \"fix the bug in auth.ts\". Use this whenever the transcript " +
            "describes work to be *done*, not just something to navigate to, find, or a " +
            "fixed app action from the rest of this grammar.",
    )
    data class AskAgent(
        @property:LLMDescription("the task/instruction, as heard, to hand to the coding agent")
        val instruction: String,
        @property:LLMDescription("only if the user named a model for this request (\"using Opus\"), as heard; else null")
        val model: String? = null,
        @property:LLMDescription("only if the user named a permission mode for this request, as heard; else null")
        val mode: String? = null,
        @property:LLMDescription("true only if the user asked for a new worktree for this request; else null")
        val worktree: Boolean? = null,
    ) : VoiceAction()

    @Serializable
    @SerialName("set_mode")
    @LLMDescription(
        "Change the permission mode new agents start in — \"plan mode\", \"accept edits\", " +
            "\"manual mode\", \"auto mode\", \"bypass permissions\". Persists until changed again.",
    )
    data class SetMode(@property:LLMDescription("the mode as heard") val mode: String) : VoiceAction()

    @Serializable
    @SerialName("set_model")
    @LLMDescription(
        "Change the agent and/or model new agents use — an agent is claude, codex, pi or omp " +
            "(\"oh my pi\"): \"use codex\", \"switch to pi\", \"use Opus\", \"switch to Sonnet\". Persists.",
    )
    data class SetModel(@property:LLMDescription("the agent or model name as heard") val model: String) : VoiceAction()

    @Serializable
    @SerialName("set_worktree")
    @LLMDescription(
        "Turn the worktree checkbox on/off — \"use a new worktree\", \"run in a worktree\", " +
            "\"stop using worktrees\". Persists.",
    )
    data class SetWorktree(@property:LLMDescription("true to turn on, false to turn off") val enabled: Boolean) : VoiceAction()

    @Serializable
    @SerialName("switch_branch")
    @LLMDescription("Switch the open project's git branch — \"switch to main\", \"checkout the paseo branch\".")
    data class SwitchBranch(@property:LLMDescription("the branch name as heard") val branch: String) : VoiceAction()

    @Serializable
    @SerialName("create_worktree")
    @LLMDescription(
        "Create a git worktree checking out a named branch — \"make a worktree for " +
            "feature-x\", \"spin up a worktree on the auth branch\", \"create a worktree " +
            "for bugfix-7\". Different from set_worktree (a persistent checkbox for new " +
            "agents) and switch_branch (moves the current checkout) — this makes a " +
            "separate checkout that stays put. If the branch already has a worktree " +
            "the app just opens it.",
    )
    data class CreateWorktree(
        @property:LLMDescription("the branch name the worktree should check out, as heard")
        val branch: String,
    ) : VoiceAction()

    @Serializable
    @SerialName("remove_worktree")
    @LLMDescription(
        "Remove/delete a git worktree — \"remove the feature-x worktree\", \"delete that " +
            "worktree\", \"clean up the worktree for the auth branch\". Destructive — the " +
            "app always confirms with the user first.",
    )
    data class RemoveWorktree(
        @property:LLMDescription("the worktree's branch name (or checkout-folder name), as heard")
        val branch: String,
    ) : VoiceAction()

    @Serializable
    @SerialName("confirm")
    @LLMDescription(
        "Say yes to whatever confirmation card is on screen — \"confirm\", \"yes\", \"do it\", " +
            "\"enable it\". Only valid when the context says a confirmation is open.",
    )
    data object Confirm : VoiceAction()

    @Serializable
    @SerialName("composer_status")
    @LLMDescription(
        "Read back the current agent settings — \"what mode am I in\", \"which model\", " +
            "\"what branch\", \"what's selected\".",
    )
    data object ComposerStatus : VoiceAction()

    @Serializable
    @SerialName("set_font_size")
    @LLMDescription(
        "Change the code editor's font size — \"set font size to 16\", \"make the code bigger\", " +
            "\"text size smaller\", \"reset the font\". Give an absolute size OR a relative change.",
    )
    data class SetFontSize(
        @property:LLMDescription("absolute size as a number (8-32) when one was named; null otherwise")
        val size: Int? = null,
        @property:LLMDescription("when no number was named: \"larger\", \"smaller\" or \"reset\"; null otherwise")
        val change: String? = null,
    ) : VoiceAction()

    @Serializable
    @SerialName("open_url")
    @LLMDescription(
        "Open a web address in this app's own Browser view (not Chrome, and not on the agent's " +
            "machine) — \"open localhost 9000 in browser\", \"go to example.com\", \"show the " +
            "preview at port 3000\". Use this, never ask_agent, whenever the transcript asks to " +
            "open/show/visit an address or port in the browser.",
    )
    data class OpenUrl(
        @property:LLMDescription("the address as heard, compact — e.g. \"localhost:9000\", \"example.com\"")
        val url: String,
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
 * The wire shape the model fills in — flat, with every action's fields
 * laid out as optionals on one type, rather than [VoiceAction]'s sealed
 * hierarchy. Confirmed live (2026-09-06): a `oneOf`-style discriminated
 * union (which is what a Kotlin sealed class compiles to in the generated
 * JSON schema) is unreliable for structured output (seen with Gemini) — the model
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
            "new_agent, ask_agent, respond_permission, set_mode, set_model, set_worktree, " +
            "switch_branch, create_worktree, remove_worktree, composer_status, confirm, set_font_size, open_url",
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
    @property:LLMDescription(
        "type=set_mode: the mode as heard (required). type=ask_agent: ONLY when the user names a " +
            "permission mode as a setting (\"in plan mode\", \"with accept edits\", \"in manual mode\") — " +
            "never because the task mentions a plan, e.g. \"create a plan for…\" or \"plan the API\" " +
            "is just the task: leave this null. Null otherwise.",
    )
    val mode: String? = null,
    @property:LLMDescription(
        "type=set_model: the model/provider name as heard (required). type=ask_agent: a model " +
            "named for just this request, else null. Null otherwise.",
    )
    val model: String? = null,
    @property:LLMDescription(
        "type=switch_branch / create_worktree / remove_worktree: the branch name as heard " +
            "(required). Null otherwise.",
    )
    val branch: String? = null,
    @property:LLMDescription(
        "type=set_worktree: true to enable, false to disable (required). type=ask_agent: true if " +
            "a new worktree was asked for just this request, else null. Null otherwise.",
    )
    val worktree: Boolean? = null,
    @property:LLMDescription(
        "type=set_font_size: the absolute font size as a number when one was named. Null otherwise.",
    )
    val fontSize: Int? = null,
    @property:LLMDescription(
        "type=set_font_size: \"larger\", \"smaller\" or \"reset\" when no number was named. Null otherwise.",
    )
    val change: String? = null,
    @property:LLMDescription(
        "required when type=open_url: the address to open, as heard (\"localhost:9000\"). Null otherwise.",
    )
    val url: String? = null,
) {
    fun toVoiceAction(): VoiceAction? = when (type) {
        "open_url" -> url?.takeIf { it.isNotBlank() }?.let { VoiceAction.OpenUrl(it) }
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
        "ask_agent" -> instruction?.let { VoiceAction.AskAgent(it, model, mode, worktree) }
        "set_mode" -> mode?.takeIf { it.isNotBlank() }?.let { VoiceAction.SetMode(it) }
        "set_model" -> model?.takeIf { it.isNotBlank() }?.let { VoiceAction.SetModel(it) }
        "set_worktree" -> worktree?.let { VoiceAction.SetWorktree(it) }
        "switch_branch" -> branch?.takeIf { it.isNotBlank() }?.let { VoiceAction.SwitchBranch(it) }
        "create_worktree" -> branch?.takeIf { it.isNotBlank() }?.let { VoiceAction.CreateWorktree(it) }
        "remove_worktree" -> branch?.takeIf { it.isNotBlank() }?.let { VoiceAction.RemoveWorktree(it) }
        "composer_status" -> VoiceAction.ComposerStatus
        "set_font_size" -> if (fontSize != null || change != null) VoiceAction.SetFontSize(fontSize, change) else null
        "confirm" -> VoiceAction.Confirm
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
    /** True when a confirmation card (delete, discard, Bypass…) is open — makes "yes"/"confirm" mean confirm. */
    val hasConfirmation: Boolean = false,
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
- The Code view has a composer with mode / model / branch / worktree settings for new agents.
  "plan mode", "accept edits", "manual mode", "bypass permissions" -> set_mode; "use Opus",
  "switch to Sonnet", "use codex", "switch to pi", "use omp" / "oh my pi" -> set_model (the agent
  and model dropdowns); "use a new worktree" / "no worktree" -> set_worktree;
  "switch to <branch>" / "checkout <branch>" -> switch_branch; "make/create a worktree for
  <branch>" -> create_worktree; "remove/delete the <branch> worktree" -> remove_worktree;
  "open git", "show branches" or "show worktrees" -> navigate with view=code (the branch
  menu lives there now); "what mode / model / branch am I on" -> composer_status.
- When ONE sentence both names settings and a task ("in a new worktree using Opus in plan
  mode, add retry logic") return a single ask_agent with the task in `instruction` and the
  named settings in its mode/model/worktree fields — do NOT split them into set_* steps, which
  would change the defaults permanently.
- Never set ask_agent's `mode` from the words of the task itself. "Create a step-by-step plan
  for the API", "plan the migration", "write an edit script", "ask before changing things"
  describe the work, not a permission mode — mode stays null, so the agent starts in the
  default Auto mode. Only an explicit mode phrase ("in plan mode", "use plan mode", "with
  accept edits", "in manual mode", "bypass permissions") sets it.
- Font size: "set the font size to 16", "text size 20" -> set_font_size with fontSize=16 / 20.
  "bigger", "larger", "zoom in on the code", "increase the font" -> set_font_size with
  change="larger"; "smaller", "decrease", "shrink the text" -> change="smaller"; "reset the
  font" / "default font size" -> change="reset". Give fontSize OR change, never both.
- Opening an address or port in the browser ("open localhost 9000 in browser", "go to
  example.com", "show port 3000") is open_url with url="localhost:9000" / "example.com" /
  "localhost:3000" — never ask_agent, which would launch a browser on the agent's machine.
- A name with a file extension ("open main.ts", "show server.ts") is open_file with that name as
  the path — never navigate. "main" in a filename is not the home view; the leading "open"
  is not part of the path.
- Speech-to-text transcripts can be rough (dropped words, filenames spoken oddly, e.g.
  "package json" for "package.json") — use judgment.
- Only use confirm when the context says a confirmation card is open ("confirm", "yes do
  it", "enable it"); it takes priority over respond_permission for a bare "yes" then.
- Only use respond_permission when the context line says a permission is pending. A bare
  "yes"/"no"/"allow it"/"deny" with nothing pending almost never fits anything else either —
  fall back to search with the raw transcript rather than inventing an action.
"""

/**
 * One OpenRouter chat-completions call that classifies a voice transcript into a [VoiceCommandPlan].
 * One instance per app process; the underlying client is just an HTTP client.
 *
 * The request is sent to [baseUrl] — normally lenscode-bridge's `/openrouter/` proxy, where the bridge
 * holds the real key, so none is sent from here and [extraHeaders] (read per request) carries this
 * device's credentials instead. Pass [apiKey] to call OpenRouter directly (tests, or no bridge).
 *
 * The model is asked for plain JSON and the reply is parsed leniently (fences, prose around the
 * object), because which structured-output modes a model supports varies by provider.
 */
class OpenRouterVoiceRouter(
    private val modelId: String,
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val extraHeaders: () -> Map<String, String> = { emptyMap() },
    private val client: OkHttpClient = OkHttpClient(),
) : AddressEditor {
    suspend fun route(transcript: String, context: VoiceRouterContext): Result<VoiceCommandPlan> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reply = chat(systemPrompt(), userPrompt(transcript, context))
                parsePlan(reply)
            }
        }

    /**
     * The address after a spoken correction ("it should be 103.61 not 103.69", "add slash docs",
     * "make it http"), worked out by the model from [current] and the [spoken] transcript. The reply is
     * checked ([parseEditedAddress]); an unusable one is a failure, never a guess.
     */
    override suspend fun editAddress(current: String, spoken: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                parseEditedAddress(chat(ADDRESS_EDIT_PROMPT, "Current address: $current\nUser said: \"$spoken\""))
            }
        }

    private fun chat(system: String, user: String): String {
        val body = JSONObject()
            .put("model", modelId)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            // Not turning reasoning off: z-ai/glm-5.3-flash rejects requests that do (HTTP 400, "reasoning is mandatory").
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply {
                apiKey?.let { header("Authorization", "Bearer $it") }
                extraHeaders().forEach { (name, value) -> header(name, value) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "OpenRouter HTTP ${response.code}: ${errorMessage(text)}" }
            return replyContent(text)
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** Worked examples shown to the model in the system prompt. */
internal object OpenRouterVoiceRouterExamples {
    val all = listOf(
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
        // "plan" here is the task, not a permission mode: mode stays null.
        VoiceCommandPlan(
            listOf(VoiceStep(type = "ask_agent", instruction = "create a step-by-step plan for the dockerfile and API endpoints")),
        ),
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
        VoiceCommandPlan(listOf(VoiceStep(type = "set_mode", mode = "plan"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "set_model", model = "opus"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "set_worktree", worktree = true))),
        VoiceCommandPlan(listOf(VoiceStep(type = "switch_branch", branch = "main"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "create_worktree", branch = "feature-x"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "remove_worktree", branch = "feature-x"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "composer_status"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "set_font_size", fontSize = 18))),
        VoiceCommandPlan(listOf(VoiceStep(type = "set_font_size", change = "larger"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "open_url", url = "localhost:9000"))),
        VoiceCommandPlan(listOf(VoiceStep(type = "confirm"))),
        VoiceCommandPlan(
            listOf(
                VoiceStep(
                    type = "ask_agent",
                    instruction = "add retry logic to the bridge",
                    model = "opus",
                    mode = "plan",
                    worktree = true,
                ),
            ),
        ),
    )
}

// ---------------------------------------------------------------------------
// Prompt and reply handling — plain functions so they can be unit-tested.
// ---------------------------------------------------------------------------

private val planJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private val exampleJson = Json { encodeDefaults = false }

internal fun userPrompt(transcript: String, context: VoiceRouterContext): String {
    val contextLine = "Current state: view=${context.activeView}, " +
        "diffFileOpen=${context.diffFileOpen ?: "none"}, " +
        "terminalSelected=${context.terminalSelected}, " +
        "agentCardOpen=${context.agentCardOpen ?: "none"}, " +
        "hasPendingPermission=${context.hasPendingPermission}, " +
        "hasConfirmation=${context.hasConfirmation}."
    return "$contextLine\n\nTranscript: \"$transcript\""
}

/** The routing rules, then the exact JSON shape (with each field's meaning) and worked examples. */
@OptIn(ExperimentalSerializationApi::class)
internal fun systemPrompt(): String {
    val descriptor = VoiceStep.serializer().descriptor
    val fields = (0 until descriptor.elementsCount).joinToString("\n") { i ->
        val description = descriptor.getElementAnnotations(i).filterIsInstance<LLMDescription>().firstOrNull()?.description
        "- ${descriptor.getElementName(i)}: ${description ?: ""}".trimEnd()
    }
    val examples = OpenRouterVoiceRouterExamples.all.joinToString("\n") {
        exampleJson.encodeToString(VoiceCommandPlan.serializer(), it)
    }
    return SYSTEM_PROMPT.trim() + "\n\n" +
        "Reply with ONLY a JSON object, no prose and no code fences: " +
        "{\"actions\":[{\"type\":\"...\", ...}]}. Each action has a `type` plus only the fields it needs " +
        "(omit the rest). Fields:\n$fields\n\nExamples of valid replies:\n$examples"
}

/** The model's text → a plan. Tolerates a ```json fence or sentences around the object. */
internal fun parsePlan(reply: String): VoiceCommandPlan {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    require(start in 0 until end) { "The model replied with no JSON object." }
    return planJson.decodeFromString(VoiceCommandPlan.serializer(), reply.substring(start, end + 1))
}

internal fun replyContent(responseBody: String): String {
    val content = JSONObject(responseBody)
        .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
    require(!content.isNullOrBlank()) { "The model sent an empty reply." }
    return content
}

internal fun errorMessage(responseBody: String): String = runCatching {
    JSONObject(responseBody).optJSONObject("error")?.optString("message")
}.getOrNull().takeUnless { it.isNullOrBlank() } ?: responseBody.take(200)

internal const val ADDRESS_EDIT_PROMPT = """
You fix a web address after the user speaks a correction. You get the CURRENT address and the speech-to-text
transcript of what they said. Reply with ONLY a JSON object {"address":"<the complete new address>"} — no
prose, no code fences, no spaces inside the address.
Rules:
- Apply the correction to the current address and keep everything else exactly as it is: scheme, host, port,
  path and query.
- The transcript may be a correction ("it should be 103.61 not 103.69", "make the last digit 1", "change 8000
  to 8001", "add slash docs", "make it http", "remove the port"), may spell numbers ("one oh three" is 103), or
  may be a whole new address.
- "X not Y", "X instead of Y" and "change Y to X" mean: replace Y with X wherever Y appears in the current
  address (match the digits and dots literally).
- Spoken symbols: dot is ".", colon is ":", slash is "/", dash is "-".
- A whole new address replaces the current one. Digits spoken one at a time ("one nine two dot one six eight
  dot zero dot ten") are 192.168.0.10.
- Never add parts the user did not say. If you cannot tell what they want, return the current address
  unchanged.
Examples (current -> said -> reply):
http://100.96.103.69:8000/ -> "it should be 103.61 not 103.69" -> {"address":"http://100.96.103.61:8000/"}
http://100.96.103.69:8000/ -> "make the last digit 1" -> {"address":"http://100.96.103.61:8000/"}
http://192.168.1.157:3000 -> "change the port to 3001" -> {"address":"http://192.168.1.157:3001"}
http://192.168.1.157:3000 -> "actually add slash docs" -> {"address":"http://192.168.1.157:3000/docs"}
https://example.com/docs -> "make it http" -> {"address":"http://example.com/docs"}
http://10.0.0.5:8080/app -> "it should be 5 not 8" -> {"address":"http://10.0.0.5:8080/app"}
"""

private val addressShape = Regex("""^([A-Za-z][A-Za-z0-9+.-]*://)?[^\s/?#]+(/\S*|\?\S*)?$""")

/** The model's reply → the address, or an error when it isn't one (so a bad reply never lands in the field). */
internal fun parseEditedAddress(reply: String): String {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    require(start in 0 until end) { "The model replied with no JSON object." }
    val address = JSONObject(reply.substring(start, end + 1)).optString("address", "").trim()
    require(address.isNotEmpty() && address.length <= MAX_ADDRESS_LENGTH && addressShape.matches(address)) {
        "The model did not return a usable address."
    }
    return address
}

private const val MAX_ADDRESS_LENGTH = 300
