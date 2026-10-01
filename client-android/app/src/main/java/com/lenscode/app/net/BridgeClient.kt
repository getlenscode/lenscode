package com.lenscode.app.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class PaneInfo(
    val terminalId: String,
    val workspaceId: String?,
    val cwd: String,
    val title: String,
    /** Paseo's terminal activity state (idle/working/attention), or null if unknown.
     * Replaces the old Herdr "focused" flag — Paseo has no server-side terminal
     * focus (docs/paseo-migration-plan.md, Phase 1), so this is what the status
     * dot actually reflects now. */
    val agentStatus: String?,
    /** "local" or a connected remote machine id (spec §37) — carried per-terminal so
     * terminal-scoped actions (read/close/interrupt) always target the right
     * machine, regardless of which machine's terminal list is on screen. */
    val machineId: String = "local",
)

data class DirEntry(val name: String, val isDir: Boolean, val language: String?)

data class FileContent(val path: String, val content: String, val language: String?, val truncated: Boolean)

/**
 * A first-class Paseo agent (docs/paseo-migration-plan.md, Phase 2/3) — not a
 * terminal pane. Herdr had no agent model at all; LensCode used to infer one by
 * scraping terminal output for a pane Herdr flagged as agent-shaped
 * (`AgentInfo` used to carry a `paneId` for exactly that reason). Paseo
 * tracks agents as their own objects instead.
 */
data class AgentInfo(
    val agentId: String,
    val provider: String,
    val model: String?,
    val title: String?,
    val status: String,
    val cwd: String,
    val workspaceId: String?,
    val requiresAttention: Boolean,
    val attentionReason: String?,
    val pendingPermissionCount: Int,
    val lastError: String?,
    val costUsd: Double?,
    /** "local" or the connected remote host this agent runs on. */
    val machineId: String = "local",
) {
    /** Chip/card label — falls back to the provider name when the agent has no title. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: provider
}

/**
 * One archived/closed agent (docs/snip-and-agent-management-plan.md) — read
 * only, so it carries none of AgentInfo's live-only fields (pending
 * permissions, cost, attention). [toAgentInfo] adapts it into an [AgentInfo]
 * with `status = "closed"` so it can be shown through the existing
 * [com.lenscode.app.ui.AgentDetailOverlay] without a second card implementation —
 * that overlay already hides its prompt box for a closed agent.
 */
data class AgentHistoryEntry(
    val agentId: String,
    val provider: String,
    val model: String?,
    val title: String?,
    val status: String,
    val cwd: String,
    val createdAt: String,
    val archivedAt: String?,
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: provider

    fun toAgentInfo(): AgentInfo = AgentInfo(
        agentId = agentId,
        provider = provider,
        model = model,
        title = title,
        status = "closed",
        cwd = cwd,
        workspaceId = null,
        requiresAttention = false,
        attentionReason = null,
        pendingPermissionCount = 0,
        lastError = null,
        costUsd = null,
    )
}

/**
 * One entry in an agent's own activity feed (docs/paseo-migration-plan.md
 * follow-up) — a message it sent/received, or a tool call it made. Until
 * this existed, the only way to see what an agent was actually doing (e.g.
 * which shell commands a voice-created agent ran) was querying the Paseo
 * daemon directly from a shell — there was no in-app view of it at all.
 */
data class AgentTimelineEntry(
    val kind: String,
    val text: String?,
    val toolName: String?,
    val command: String?,
    val output: String?,
    val status: String?,
    val timestamp: String,
)

/** One pending "approve this tool call" request (docs/paseo-migration-plan.md, Phase 3). */
data class PermissionAction(val id: String, val label: String, val behavior: String)

data class PermissionInfo(
    val permissionRequestId: String,
    val agentId: String,
    val kind: String,
    val title: String?,
    val description: String?,
    val actions: List<PermissionAction>,
)

data class WorktreeInfo(
    val path: String,
    val branch: String?,
    val openWorkspaceId: String?,
)

data class GitFileStatus(val path: String, val oldPath: String?, val status: String)

data class VoiceCandidate(val path: String, val score: Double, val preview: String?)

/** A typed `search.files` hit (spec §13 back-end) — filename or ripgrep text match. */
data class FileSearchHit(val path: String, val score: Double, val kind: String, val preview: String?, val line: Int?)

data class VoiceResolution(val confident: Boolean, val resolvedPath: String?, val candidates: List<VoiceCandidate>)

data class GitStatus(val branch: String?, val files: List<GitFileStatus>)

/** One coding-agent provider from `provider.list`, with the model ids it offers. */
data class ProviderInfo(val provider: String, val available: Boolean, val models: List<String>)

data class MachineInfo(val id: String, val label: String, val connected: Boolean = false)

/** Client-side view of the pairing handshake (spec §55) — drives the pairing card. */
sealed class PairingState {
    /** Not paired yet and no pairing in progress; unauthenticated in prod mode. */
    object NeedsPairing : PairingState()
    /** pair.begin succeeded; code is shown on the bridge's own console, not here. */
    data class CodeSent(val deviceName: String) : PairingState()
    data class Failed(val deviceName: String, val message: String) : PairingState()
    /** Either dev mode (pairing skipped) or pair.complete already succeeded. */
    object Authenticated : PairingState()
}

/**
 * WebSocket client for lenscode-bridge speaking XR protocol v3 (see ../protocol).
 * Runs the initial-sync sequence (spec §7): hello → snapshot → subscribe, and
 * resyncs on every reconnect because backend state wins (spec §58–59).
 *
 * Requests are correlated by id (a callback per pending request) rather than
 * routed by guessing the result's shape, since several request types now
 * share overlapping field names.
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val url: String,
    private val deviceId: String,
    private val pairingStore: PairingStore,
) {
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.NeedsPairing)
    val pairingState: StateFlow<PairingState> = _pairingState
    data class UiState(
        val bridgeConnected: Boolean = false,
        val backendConnected: Boolean = false,
        val workspaces: Int = 0,
        val panes: Int = 0,
        val agents: Int = 0,
        val lastEvent: String = "—",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _terminals = MutableStateFlow<List<PaneInfo>>(emptyList())
    val terminals: StateFlow<List<PaneInfo>> = _terminals

    private val _terminalOutput = MutableStateFlow<String?>(null)
    val terminalOutput: StateFlow<String?> = _terminalOutput

    private val _dirPath = MutableStateFlow(".")
    val dirPath: StateFlow<String> = _dirPath

    private val _dirEntries = MutableStateFlow<List<DirEntry>>(emptyList())
    val dirEntries: StateFlow<List<DirEntry>> = _dirEntries

    private val _openFile = MutableStateFlow<FileContent?>(null)
    val openFile: StateFlow<FileContent?> = _openFile

    private val _voiceResult = MutableStateFlow<VoiceResolution?>(null)
    val voiceResult: StateFlow<VoiceResolution?> = _voiceResult

    private val _searchResults = MutableStateFlow<List<FileSearchHit>>(emptyList())
    val searchResults: StateFlow<List<FileSearchHit>> = _searchResults

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents

    private val _agentHistory = MutableStateFlow<List<AgentHistoryEntry>>(emptyList())
    val agentHistory: StateFlow<List<AgentHistoryEntry>> = _agentHistory

    private val _agentHistoryHasMore = MutableStateFlow(false)
    val agentHistoryHasMore: StateFlow<Boolean> = _agentHistoryHasMore

    private var agentHistoryCursor: String? = null

    private val _agentTimeline = MutableStateFlow<List<AgentTimelineEntry>>(emptyList())
    val agentTimeline: StateFlow<List<AgentTimelineEntry>> = _agentTimeline

    /** Which agent's timeline to keep live — set by the agent detail card while it's open, null while closed. */
    private var trackedAgentId: String? = null

    private val _permissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
    val permissions: StateFlow<List<PermissionInfo>> = _permissions

    private val _worktrees = MutableStateFlow<List<WorktreeInfo>>(emptyList())
    val worktrees: StateFlow<List<WorktreeInfo>> = _worktrees

    private val _machines = MutableStateFlow<List<MachineInfo>>(emptyList())
    val machines: StateFlow<List<MachineInfo>> = _machines

    private val _gitStatus = MutableStateFlow<GitStatus?>(null)
    val gitStatus: StateFlow<GitStatus?> = _gitStatus

    private val _gitDiffPath = MutableStateFlow<String?>(null)
    val gitDiffPath: StateFlow<String?> = _gitDiffPath

    private val _gitDiffText = MutableStateFlow<String?>(null)
    val gitDiffText: StateFlow<String?> = _gitDiffText

    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private val nextId = AtomicLong(1)
    private var closedByUs = false
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    private val errorCallbacks = mutableMapOf<String, (JSONObject) -> Unit>()

    fun connect() {
        closedByUs = false
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener())
    }

    fun close() {
        closedByUs = true
        ws?.close(1000, "bye")
    }

    private fun sendHello() {
        val fields = JSONObject()
            .put("protocolVersion", 3)
            .put("deviceId", deviceId)
            .put(
                "clientInfo",
                JSONObject()
                    .put("app", "lenscode-xr-android")
                    .put("version", "0.1.0")
                    .put("platform", "android-xr"),
            )
        pairingStore.loadToken()?.let { fields.put("deviceToken", it) }
        send(
            "hello",
            fields,
            onResult = { helloResult ->
                Log.d("LensCodeBridge", "hello result received: $helloResult")
                _state.value = _state.value.copy(
                    bridgeConnected = true,
                    backendConnected = helloResult.optBoolean("backendConnected", false),
                )
                if (helloResult.optBoolean("authenticated", false)) {
                    _pairingState.value = PairingState.Authenticated
                    onAuthenticated()
                } else {
                    // Dev mode always authenticates; only prod, unpaired devices land
                    // here. Nothing but hello/pair.* is reachable until this resolves.
                    _pairingState.value = PairingState.NeedsPairing
                }
            },
        )
    }

    private fun onAuthenticated() {
        send("snapshot.request", onResult = { snapshotResult -> applySnapshot(snapshotResult) })
        send("events.subscribe", JSONObject().put("topics", JSONArray().put("backend")))
        refreshTerminals()
        refreshAgents()
        refreshPermissions()
        refreshProviders()
        refreshBranches()
        refreshWorktrees()
        // So Home's project-path summary has something to show immediately,
        // not only after the user visits Code at least once.
        listDir(dirPath.value)
    }

    /** Starts pairing (spec §55) — the 6-digit code itself is shown on the bridge's own console. */
    fun beginPairing(deviceName: String) {
        send(
            "pair.begin",
            JSONObject().put("deviceId", deviceId).put("deviceName", deviceName),
            onResult = { _pairingState.value = PairingState.CodeSent(deviceName) },
            onError = { error ->
                _pairingState.value = PairingState.Failed(
                    deviceName,
                    error.optString("message", "Could not start pairing."),
                )
            },
        )
    }

    /** Submits the code a human read off the bridge console and typed/spoke into XR. */
    fun completePairing(deviceName: String, code: String) {
        send(
            "pair.complete",
            JSONObject().put("deviceId", deviceId).put("pairingCode", code),
            onResult = { result ->
                pairingStore.saveToken(result.getString("deviceToken"))
                _pairingState.value = PairingState.Authenticated
                onAuthenticated()
            },
            onError = { error ->
                // Stay in CodeSent (not NeedsPairing) so a wrong digit doesn't force
                // starting the whole flow — and thus the code — over again.
                _pairingState.value = PairingState.Failed(
                    deviceName,
                    error.optString("message", "That pairing code did not work."),
                )
            },
        )
    }

    fun retryPairingAfterFailure(deviceName: String) {
        _pairingState.value = PairingState.CodeSent(deviceName)
    }

    private fun send(
        type: String,
        fields: JSONObject = JSONObject(),
        onResult: ((JSONObject) -> Unit)? = null,
        onError: ((JSONObject) -> Unit)? = null,
    ) {
        val id = "a${nextId.getAndIncrement()}"
        fields.put("id", id)
        fields.put("type", type)
        if (onResult != null) callbacks[id] = onResult
        if (onError != null) errorCallbacks[id] = onError
        ws?.send(fields.toString())
    }

    // ── Terminals ────────────────────────────────────────────────────────
    // "local" or a connected remote machine id (spec §37) — every call takes
    // it explicitly rather than trailing a stored "current machine", since
    // the agent detail card shares several of these functions and its
    // targets are always local regardless of which machine the Terminal
    // view happens to be showing.

    private val _terminalsMachineId = MutableStateFlow("local")
    val terminalsMachineId: StateFlow<String> = _terminalsMachineId

    /** Switches which machine's terminal list the Terminal view shows, and reloads it. */
    fun switchTerminalsMachine(machineId: String) {
        _terminalsMachineId.value = machineId
        _terminals.value = emptyList()
        refreshTerminals(machineId)
    }

    fun refreshTerminals(machineId: String = _terminalsMachineId.value, onDone: () -> Unit = {}) {
        send(
            "terminal.list",
            JSONObject().put("machineId", machineId),
            onResult = { result ->
                val arr = result.optJSONArray("terminals") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    PaneInfo(
                        terminalId = t.optString("terminalId"),
                        workspaceId = if (t.isNull("workspaceId")) null else t.optString("workspaceId"),
                        cwd = if (t.isNull("cwd")) "" else t.optString("cwd", ""),
                        title = t.optString("name", t.optString("terminalId")),
                        agentStatus = if (t.isNull("agentStatus")) null else t.optString("agentStatus"),
                        machineId = machineId,
                    )
                }
                _terminals.value = list
                onDone()
            },
        )
    }

    fun readTerminal(terminalId: String, machineId: String = "local") {
        send(
            "terminal.read",
            JSONObject().put("terminalId", terminalId).put("machineId", machineId).put("source", "visible").put("format", "text"),
            onResult = { result ->
                _terminalOutput.value = result.optString("content", "")
            },
        )
    }

    /**
     * One-off read that doesn't touch [terminalOutput] — used by the agent
     * detail card's preview, which must not clobber whatever the Terminal
     * view currently has selected.
     */
    fun peekTerminal(terminalId: String, machineId: String = "local", onResult: (String) -> Unit) {
        send(
            "terminal.read",
            JSONObject().put("terminalId", terminalId).put("machineId", machineId).put("source", "visible").put("format", "text"),
            onResult = { result -> onResult(result.optString("content", "")) },
        )
    }

    fun createTerminal(
        name: String? = null,
        machineId: String = _terminalsMachineId.value,
        cwd: String? = null,
        onCreated: (String) -> Unit = {},
        // No-op default kept for existing call sites — but a silently swallowed
        // failure here means an entire caller chain (e.g. ask_agent's
        // create-terminal-then-create-agent flow) just goes quiet with zero
        // trace anywhere (not logged server-side either — see server.ts's
        // terminal.* case, which only logs on an unexpected exception, not a
        // normal backend_unavailable response). Pass a real handler for any
        // flow where "it did nothing" would otherwise be undiagnosable.
        onError: (JSONObject) -> Unit = {},
    ) {
        val fields = JSONObject().put("machineId", machineId)
        if (name != null) fields.put("name", name)
        if (cwd != null) fields.put("cwd", cwd)
        send(
            "terminal.create",
            fields,
            onResult = { result ->
                // Only actually switch (which clears the list first) if this created
                // a terminal on a different machine than the Terminal view is showing —
                // the common "+ New" case targets the current one and should just refresh.
                if (_terminalsMachineId.value != machineId) switchTerminalsMachine(machineId) else refreshTerminals(machineId)
                onCreated(result.optJSONObject("terminal")?.optString("terminalId") ?: "")
            },
            onError = onError,
        )
    }

    /**
     * Types raw text into a terminal's pty, as if typed at the keyboard —
     * include a trailing "\n" to actually submit a command. Used to hand a
     * terminal pane over to tmux (docs/paseo-migration-plan.md follow-up:
     * ask_agent's tmux-bridged terminal) rather than anything a human
     * normally does through the UI, which is why there's no dock affordance
     * for this — it's script, not chat.
     */
    fun inputTerminal(terminalId: String, text: String, machineId: String = "local") {
        send(
            "terminal.input",
            JSONObject().put("terminalId", terminalId).put("text", text).put("machineId", machineId),
        )
    }

    fun closeTerminal(terminalId: String, machineId: String = "local", onDone: () -> Unit = {}) {
        send("terminal.close", JSONObject().put("terminalId", terminalId).put("machineId", machineId), onResult = {
            refreshTerminals(machineId)
            onDone()
        })
    }

    /**
     * Ctrl+C — the protocol requires `confirmed:true`, so only call this after
     * the XR confirmation card. Paseo has no per-terminal "what's running"
     * RPC (Herdr's `terminal.process_info` has no equivalent — see
     * docs/paseo-migration-plan.md), so unlike before, the confirmation card
     * can't name the process being interrupted; it just confirms the action.
     */
    fun interruptTerminal(terminalId: String, machineId: String = "local", onDone: () -> Unit = {}) {
        send(
            "terminal.interrupt",
            JSONObject().put("terminalId", terminalId).put("machineId", machineId).put("confirmed", true),
            onResult = {
                readTerminal(terminalId, machineId)
                onDone()
            },
        )
    }

    // ── Files ────────────────────────────────────────────────────────────

    /**
     * Which Herdr workspace the file browser is rooted at. "local" resolves
     * bridge-side to lenscode-bridge's own project root; any real Herdr
     * workspace_id resolves to that workspace's pane cwd (see lenscode-bridge's
     * `filesFor`) — which is exactly a worktree's checkout path, so jumping
     * the Code view into a worktree is just switching this id.
     */
    private var filesWorkspaceId = "local"
    /** The workspace Code is currently showing — what a new agent should start in. "local" = the bridge's project root. */
    val currentWorkspaceId: String get() = filesWorkspaceId
    /** "local" or a connected remote machine id — which machine `filesWorkspaceId` is interpreted against. */
    private var filesMachineId = "local"

    private val _filesRootLabel = MutableStateFlow("local")
    val filesRootLabel: StateFlow<String> = _filesRootLabel

    // Home view's "current project/machine/path" summary (spec follow-up) —
    // filesMachineId itself stays a plain private var (every request site
    // reads it directly), these just mirror it for display.
    private val _filesMachineIdFlow = MutableStateFlow("local")
    val filesMachineIdFlow: StateFlow<String> = _filesMachineIdFlow

    private val _projectRootPath = MutableStateFlow<String?>(null)
    /** Absolute path of whatever the file browser is currently rooted at — local or remote, never an IP (spec §54/§36). */
    val projectRootPath: StateFlow<String?> = _projectRootPath

    /** Re-roots the file browser at a worktree (or any Herdr workspace) and opens it in Code. Defaults to local. */
    fun openWorkspaceInFiles(workspaceId: String, label: String, machineId: String = "local") {
        filesMachineId = machineId
        _filesMachineIdFlow.value = machineId
        filesWorkspaceId = workspaceId
        _filesRootLabel.value = label
        listDir(".")
    }

    /**
     * Re-roots the file browser at an absolute folder on a remote machine
     * (spec §37) — e.g. a folder picked in the remote browse dialog. Unlike
     * [openWorkspaceInFiles], `root` isn't a Herdr workspace id to resolve;
     * it's used as the literal filesystem root, matching how the bridge's
     * `RemoteFiles` treats it (see lenscode-bridge's resolveFiles).
     */
    fun openRemoteFolderInFiles(machineId: String, root: String, label: String) {
        filesMachineId = machineId
        _filesMachineIdFlow.value = machineId
        filesWorkspaceId = root
        _filesRootLabel.value = label
        listDir(".")
    }

    fun listDir(path: String) {
        send(
            "dir.list",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    DirEntry(
                        name = e.optString("name"),
                        isDir = e.optString("kind") == "dir",
                        language = if (e.isNull("language")) null else e.optString("language"),
                    )
                }
                _dirPath.value = path
                _dirEntries.value = list
                _openFile.value = null
                _projectRootPath.value = if (result.isNull("root")) null else result.optString("root")
            },
        )
    }

    fun openFile(path: String) {
        send(
            "file.open",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                _openFile.value = FileContent(
                    path = result.optString("path", path),
                    content = result.optString("content", ""),
                    language = if (result.isNull("language")) null else result.optString("language"),
                    truncated = result.optBoolean("truncated", false),
                )
            },
        )
    }

    // ── Voice file search ────────────────────────────────────────────────
    // Never silently guesses (spec §13, §63): a confident hit still comes
    // back with candidates attached, and an unconfident one carries no
    // resolvedPath at all — the UI decides what "confident enough" means to
    // show, but it can never open a file the bridge itself wasn't sure about.

    fun voiceSearch(query: String) {
        _voiceResult.value = null
        send(
            "voice.search",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("query", query),
            onResult = { result ->
                val arr = result.optJSONArray("candidates") ?: JSONArray()
                val candidates = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    VoiceCandidate(
                        path = c.optString("path"),
                        score = c.optDouble("score", 0.0),
                        preview = if (c.isNull("preview")) null else c.optString("preview"),
                    )
                }
                _voiceResult.value = VoiceResolution(
                    confident = result.optBoolean("confident", false),
                    resolvedPath = if (result.isNull("resolvedPath")) null else result.optString("resolvedPath"),
                    candidates = candidates,
                )
            },
        )
    }

    fun clearVoiceResult() {
        _voiceResult.value = null
    }

    // ── Typed file search (spec §13 back-end) ───────────────────────────
    // Distinct from voiceSearch: this never auto-opens anything — it's a
    // plain results list the human picks from, no confidence gating needed.

    fun searchFiles(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        send(
            "search.files",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("query", query).put("limit", 20),
            onResult = { result ->
                val arr = result.optJSONArray("hits") ?: JSONArray()
                _searchResults.value = (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    FileSearchHit(
                        path = h.optString("path"),
                        score = h.optDouble("score", 0.0),
                        kind = h.optString("kind", "filename"),
                        preview = if (h.isNull("preview")) null else h.optString("preview"),
                        line = if (h.isNull("line")) null else h.optInt("line"),
                    )
                }
            },
        )
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    // ── Git diff ─────────────────────────────────────────────────────────
    // Always scoped to whatever workspace the Code view is currently rooted
    // at (filesWorkspaceId) — "what changed" should track "what I'm browsing".

    fun refreshGitStatus() {
        send(
            "git.status",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId),
            onResult = { result ->
                val arr = result.optJSONArray("files") ?: JSONArray()
                val files = (0 until arr.length()).map { i ->
                    val f = arr.getJSONObject(i)
                    GitFileStatus(
                        path = f.optString("path"),
                        oldPath = if (f.isNull("oldPath")) null else f.optString("oldPath"),
                        status = f.optString("status"),
                    )
                }
                _gitStatus.value = GitStatus(
                    branch = if (result.isNull("branch")) null else result.optString("branch"),
                    files = files,
                )
                _gitDiffPath.value = null
                _gitDiffText.value = null
            },
        )
    }

    // ── Branch switcher ──────────────────────────────────────────────────

    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providers: StateFlow<List<ProviderInfo>> = _providers

    /** Fetched at connect time — model discovery can be slow on a cold provider, so the picker shouldn't wait on it. */
    fun refreshProviders() {
        send(
            "provider.list",
            onResult = { result ->
                val arr = result.optJSONArray("providers") ?: JSONArray()
                _providers.value = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    val models = p.optJSONArray("models") ?: JSONArray()
                    ProviderInfo(
                        provider = p.optString("provider"),
                        available = p.optBoolean("available", false),
                        models = (0 until models.length()).map { models.getString(it) },
                    )
                }
            },
        )
    }

    private val _branches = MutableStateFlow<List<String>>(emptyList())
    val branches: StateFlow<List<String>> = _branches

    private val _currentBranch = MutableStateFlow<String?>(null)
    val currentBranch: StateFlow<String?> = _currentBranch

    fun refreshBranches() {
        send(
            "git.branches",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId),
            onResult = { result ->
                val arr = result.optJSONArray("branches") ?: JSONArray()
                _branches.value = (0 until arr.length()).map { arr.getString(it) }
                _currentBranch.value = if (result.isNull("current")) null else result.optString("current")
            },
        )
    }

    /**
     * Switches to [target] in the currently-browsed workspace. [onDirty] is
     * omitted on the first attempt; if the tree has uncommitted changes the
     * bridge refuses with a structured "dirty" conflict rather than picking a
     * resolution itself (spec §27) — [onConflict] gets called so the UI can
     * ask the user to choose, then this is called again with their answer.
     */
    fun switchBranch(
        target: String,
        onDirty: String? = null,
        onSwitched: () -> Unit,
        onConflict: () -> Unit,
        /** (workspaceId, machineId) — the machine is whatever Code was scoped to when the switch was requested. */
        onWorktreeCreated: (String?, String) -> Unit,
    ) {
        val machineId = filesMachineId
        val fields = JSONObject()
            .put("machineId", machineId)
            .put("workspaceId", filesWorkspaceId)
            .put("target", target)
        if (onDirty != null) fields.put("onDirty", onDirty)
        send(
            "git.branch.switch",
            fields,
            onResult = { result ->
                when {
                    result.optBoolean("worktreeCreated", false) -> {
                        val wsId = if (result.isNull("workspaceId")) null else result.optString("workspaceId")
                        onWorktreeCreated(wsId, machineId)
                    }
                    result.optBoolean("switched", false) -> {
                        refreshBranches()
                        refreshGitStatus()
                        onSwitched()
                    }
                    else -> Unit // aborted — nothing changed, nothing to refresh
                }
            },
            onError = { error ->
                if (error.optString("detail") == "dirty") onConflict() else Unit
            },
        )
    }

    fun loadGitDiff(path: String) {
        _gitDiffPath.value = path
        _gitDiffText.value = null
        send(
            "git.diff",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("path", path),
            onResult = { result ->
                if (_gitDiffPath.value == path) _gitDiffText.value = result.optString("diff", "")
            },
        )
    }

    // ── Agents (docs/paseo-migration-plan.md, Phase 2/3) ────────────────
    // Agents are first-class in Paseo, unlike Herdr where LensCode inferred an
    // "agent" from a terminal pane's scraped output — see AgentInfo's doc.

    fun refreshAgents() {
        send("agent.list", onResult = { result ->
            val arr = result.optJSONArray("agents") ?: JSONArray()
            _agents.value = (0 until arr.length()).map { i -> parseAgent(arr.getJSONObject(i)) }
        })
    }

    private fun parseHistoryEntry(e: JSONObject): AgentHistoryEntry = AgentHistoryEntry(
        agentId = e.optString("agentId"),
        provider = e.optString("provider", "agent"),
        model = if (e.isNull("model")) null else e.optString("model"),
        title = if (e.isNull("title")) null else e.optString("title"),
        status = e.optString("status", "closed"),
        cwd = e.optString("cwd", ""),
        createdAt = e.optString("createdAt"),
        archivedAt = if (e.isNull("archivedAt")) null else e.optString("archivedAt"),
    )

    /** First page of agent history (docs/snip-and-agent-management-plan.md) — replaces whatever was there before. */
    fun refreshAgentHistory(search: String? = null) {
        agentHistoryCursor = null
        val fields = JSONObject()
        if (!search.isNullOrBlank()) fields.put("search", search)
        send(
            "agent.history",
            fields,
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                _agentHistory.value = (0 until arr.length()).map { i -> parseHistoryEntry(arr.getJSONObject(i)) }
                agentHistoryCursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor")
                _agentHistoryHasMore.value = result.optBoolean("hasMore", false)
            },
        )
    }

    /** Appends the next page onto [agentHistory]; no-op once [agentHistoryHasMore] is false. */
    fun loadMoreAgentHistory() {
        val cursor = agentHistoryCursor ?: return
        send(
            "agent.history",
            JSONObject().put("cursor", cursor),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                val more = (0 until arr.length()).map { i -> parseHistoryEntry(arr.getJSONObject(i)) }
                _agentHistory.value = _agentHistory.value + more
                agentHistoryCursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor")
                _agentHistoryHasMore.value = result.optBoolean("hasMore", false)
            },
        )
    }

    private fun parseAgent(a: JSONObject): AgentInfo = AgentInfo(
        agentId = a.optString("agentId"),
        provider = a.optString("provider", "agent"),
        model = if (a.isNull("model")) null else a.optString("model"),
        title = if (a.isNull("title")) null else a.optString("title"),
        status = a.optString("status", "idle"),
        cwd = a.optString("cwd", ""),
        workspaceId = if (a.isNull("workspaceId")) null else a.optString("workspaceId"),
        requiresAttention = a.optBoolean("requiresAttention", false),
        attentionReason = if (a.isNull("attentionReason")) null else a.optString("attentionReason"),
        pendingPermissionCount = a.optInt("pendingPermissionCount", 0),
        lastError = if (a.isNull("lastError")) null else a.optString("lastError"),
        costUsd = if (a.isNull("costUsd")) null else a.optDouble("costUsd"),
        machineId = a.optString("machineId", "local").also { agentMachines[a.optString("agentId")] = it },
    )

    /** agentId → machine, learned from agent.list/agent.create, so follow-up requests reach the host the agent runs on. */
    private val agentMachines = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun withMachine(agentId: String, fields: JSONObject): JSONObject {
        val machine = agentMachines[agentId]
        if (machine != null && machine != "local") fields.put("machineId", machine)
        return fields
    }

    /**
     * Starts (or stops, with null) keeping [agentTimeline] live for one agent —
     * called when the agent detail card opens/closes. Refetches immediately
     * and again on every backend event while tracked (see handleEvent); no
     * incremental agent_stream parsing, just re-pull the tail, same pattern
     * refreshAgents()/refreshTerminals() already use for everything else.
     */
    fun trackAgent(agentId: String?) {
        trackedAgentId = agentId
        if (agentId != null) refreshAgentTimeline(agentId) else _agentTimeline.value = emptyList()
    }

    fun refreshAgentTimeline(agentId: String) {
        send(
            "agent.timeline",
            withMachine(agentId, JSONObject().put("agentId", agentId).put("limit", 200)),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                _agentTimeline.value = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    AgentTimelineEntry(
                        kind = e.optString("kind", "other"),
                        text = if (e.isNull("text")) null else e.optString("text"),
                        toolName = if (e.isNull("toolName")) null else e.optString("toolName"),
                        command = if (e.isNull("command")) null else e.optString("command"),
                        output = if (e.isNull("output")) null else e.optString("output"),
                        status = if (e.isNull("status")) null else e.optString("status"),
                        timestamp = e.optString("timestamp"),
                    )
                }
            },
        )
    }

    /** Sends a chat-style turn to a running agent; fire-and-forget (watch agent_stream/refreshAgents for the reply). */
    fun sendAgentMessage(agentId: String, text: String, onDone: () -> Unit = {}, onError: (() -> Unit)? = null) {
        send(
            "agent.send",
            withMachine(agentId, JSONObject().put("agentId", agentId).put("text", text)),
            onResult = {
                refreshAgents()
                onDone()
            },
            onError = { onError?.invoke() },
        )
    }

    /**
     * Spins up a brand-new agent with an initial task (docs/paseo-migration-plan.md,
     * Phase 2) — used by voice's `ask_agent` action ("start a http server in this
     * project") when no agent card is currently open to hand the instruction to
     * instead. `provider` is required by the backend (no server-side default).
     */
    fun createAgent(
        provider: String,
        cwd: String,
        prompt: String,
        model: String? = null,
        title: String? = null,
        workspaceId: String? = null,
        /** Paseo permission mode ("auto", "bypassPermissions", …) — null keeps Paseo's own "default" (ask every time). */
        modeId: String? = null,
        /** Which machine runs the agent — a connected remote host's id, or "local". */
        machineId: String = "local",
        /** Run in a brand-new git worktree instead of [cwd]'s own checkout. */
        worktree: Boolean = false,
        onCreated: (AgentInfo) -> Unit = {},
        onError: ((String) -> Unit)? = null,
    ) {
        val fields = JSONObject()
            .put("provider", provider)
            .put("cwd", cwd)
            .put("prompt", prompt)
        if (model != null) fields.put("model", model)
        if (title != null) fields.put("title", title)
        if (workspaceId != null) fields.put("workspaceId", workspaceId)
        if (modeId != null) fields.put("modeId", modeId)
        if (worktree) fields.put("worktree", true)
        if (machineId != "local") fields.put("machineId", machineId)
        send(
            "agent.create",
            fields,
            onResult = { result ->
                refreshAgents()
                onCreated(parseAgent(result.getJSONObject("agent")))
            },
            onError = { error ->
                // The bridge's own message ("Not connected to …", "Pick a folder…") beats a generic failure.
                onError?.invoke(error.optString("detail").ifBlank { error.optString("message", "Could not create that agent.") })
            },
        )
    }

    /** Soft-deletes the agent and closes its runtime (spec §57 — caller must have already shown a confirmation card). */
    fun archiveAgent(agentId: String, onDone: () -> Unit = {}) {
        send("agent.archive", withMachine(agentId, JSONObject().put("agentId", agentId)), onResult = {
            refreshAgents()
            onDone()
        })
    }

    // ── Permissions (docs/paseo-migration-plan.md, Phase 3) ─────────────
    // The spatial "approve this tool call" card — the highest-value new
    // glasses affordance this phase adds; Herdr had no equivalent to migrate
    // away from.

    fun refreshPermissions() {
        send("permission.list", onResult = { result ->
            val arr = result.optJSONArray("permissions") ?: JSONArray()
            _permissions.value = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val actionsArr = p.optJSONArray("actions") ?: JSONArray()
                PermissionInfo(
                    permissionRequestId = p.optString("permissionRequestId"),
                    agentId = p.optString("agentId"),
                    kind = p.optString("kind", "other"),
                    title = if (p.isNull("title")) null else p.optString("title"),
                    description = if (p.isNull("description")) null else p.optString("description"),
                    actions = (0 until actionsArr.length()).map { j ->
                        val a = actionsArr.getJSONObject(j)
                        PermissionAction(a.optString("id"), a.optString("label"), a.optString("behavior"))
                    },
                )
            }
        })
    }

    /** Approves or denies one pending permission request (spec §57 — always after an explicit XR confirmation). */
    fun respondToPermission(
        permission: PermissionInfo,
        behavior: String,
        selectedActionId: String? = null,
        onDone: () -> Unit = {},
    ) {
        val fields = JSONObject()
            .put("agentId", permission.agentId)
            .put("permissionRequestId", permission.permissionRequestId)
            .put("behavior", behavior)
        if (selectedActionId != null) fields.put("selectedActionId", selectedActionId)
        withMachine(permission.agentId, fields)
        send("permission.respond", fields, onResult = {
            refreshPermissions()
            refreshAgents()
            onDone()
        })
    }

    // ── Remote machines (spec §36, registry only — no tunnel yet) ─────────

    fun refreshMachines() {
        send("machine.list", onResult = { result ->
            val arr = result.optJSONArray("machines") ?: JSONArray()
            _machines.value = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                MachineInfo(
                    id = m.optString("id"),
                    label = m.optString("label"),
                    connected = m.optBoolean("connected", false),
                )
            }
        })
    }

    private val _machineConnectError = MutableStateFlow<String?>(null)
    val machineConnectError: StateFlow<String?> = _machineConnectError

    private val _connectingMachineId = MutableStateFlow<String?>(null)
    val connectingMachineId: StateFlow<String?> = _connectingMachineId

    /**
     * Opens the SSH tunnel + remote Herdr connection (spec §37) — can take a
     * few seconds. On success, opens the remote folder browser right away
     * (starting at the machine's real home directory) so connecting always
     * leads somewhere, rather than landing on an otherwise-empty machine row.
     */
    fun connectMachine(machineId: String, label: String) {
        _machineConnectError.value = null
        _connectingMachineId.value = machineId
        send(
            "machine.connect",
            JSONObject().put("machineId", machineId),
            onResult = { result ->
                _connectingMachineId.value = null
                refreshMachines()
                val homeDir = if (result.isNull("homeDir")) null else result.optString("homeDir")
                startRemoteBrowse(machineId, label, homeDir?.trimEnd('/')?.ifBlank { "/" } ?: "/")
            },
            onError = { error ->
                _connectingMachineId.value = null
                _machineConnectError.value = error.optString("message", "Could not connect to $machineId.")
            },
        )
    }

    // ── Remote folder browser (spec §37) — picks the folder that "Connect" opens into Code + Terminal ──

    data class RemoteBrowseEntry(val name: String, val isDir: Boolean)

    data class RemoteBrowseState(
        val machineId: String,
        val machineLabel: String,
        val path: String,
        val entries: List<RemoteBrowseEntry>,
        val loading: Boolean,
    )

    private val _remoteBrowse = MutableStateFlow<RemoteBrowseState?>(null)
    val remoteBrowse: StateFlow<RemoteBrowseState?> = _remoteBrowse

    fun startRemoteBrowse(machineId: String, machineLabel: String, startPath: String) {
        _remoteBrowse.value = RemoteBrowseState(machineId, machineLabel, startPath, emptyList(), loading = true)
        fetchRemoteBrowseDir(machineId, startPath)
    }

    fun browseRemoteDir(path: String) {
        val current = _remoteBrowse.value ?: return
        _remoteBrowse.value = current.copy(loading = true)
        fetchRemoteBrowseDir(current.machineId, path)
    }

    fun browseRemoteUp() {
        val current = _remoteBrowse.value ?: return
        val parent = current.path.trimEnd('/').substringBeforeLast('/', "/").ifBlank { "/" }
        browseRemoteDir(parent)
    }

    private fun fetchRemoteBrowseDir(machineId: String, path: String) {
        // "." as the browsed path against `path` as the workspace root — same
        // convention resolveFiles uses server-side: workspaceId IS the absolute
        // root for a remote machine, not a Herdr workspace id to resolve.
        send(
            "dir.list",
            JSONObject().put("machineId", machineId).put("workspaceId", path).put("path", "."),
            onResult = { result ->
                val arr = result.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    RemoteBrowseEntry(name = e.optString("name"), isDir = e.optString("kind") == "dir")
                }
                val current = _remoteBrowse.value
                if (current != null && current.machineId == machineId) {
                    _remoteBrowse.value = current.copy(path = path, entries = entries, loading = false)
                }
            },
        )
    }

    fun closeRemoteBrowse() {
        _remoteBrowse.value = null
    }

    /** Opens the currently-browsed folder in both Code (this machine+path) and a fresh Terminal rooted there. */
    fun openRemoteBrowsedFolder() {
        val current = _remoteBrowse.value ?: return
        val folderName = current.path.trimEnd('/').substringAfterLast('/').ifBlank { current.path }
        openRemoteFolderInFiles(current.machineId, current.path, "${current.machineLabel} · $folderName")
        createTerminal(name = folderName, machineId = current.machineId, cwd = current.path)
        _remoteBrowse.value = null
    }

    fun disconnectMachine(machineId: String) {
        send(
            "machine.disconnect",
            JSONObject().put("machineId", machineId),
            onResult = { refreshMachines() },
        )
    }

    fun clearMachineConnectError() {
        _machineConnectError.value = null
    }

    // ── Snip tool ────────────────────────────────────────────────────────
    // agent.send has no image field (see protocol's snip.upload doc) — the
    // cropped PNG is written to disk on the bridge's own machine instead,
    // and the caller sends the returned path as a normal text prompt.

    fun uploadSnip(pngBase64: String, onResult: (String) -> Unit, onError: () -> Unit = {}) {
        send(
            "snip.upload",
            JSONObject().put("pngBase64", pngBase64),
            onResult = { result -> onResult(result.optString("path")) },
            onError = { onError() },
        )
    }

    // ── Git worktrees & branches ─────────────────────────────────────────
    // Scoped to whichever machine the Code view is currently rooted at
    // (filesMachineId) — worktrees, like Diff and the branch switcher,
    // follow Code rather than tracking their own separate machine context.
    //
    // docs/paseo-migration-plan.md, Phase 3: worktree.list now takes the same
    // lenscode workspaceId + machineId shape as git.status/git.branches — the
    // bridge resolves it to a cwd server-side (resolveWorkspaceRoot), so
    // unlike the old Herdr-proxy version this needs no candidate-guessing
    // across every pane's workspace on the machine.

    fun refreshWorktrees() {
        send(
            "worktree.list",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId),
            onResult = { result -> applyWorktrees(result) },
            onError = { _worktrees.value = emptyList() },
        )
    }

    /**
     * Opens a worktree's checkout in the Code view. Most worktrees we create
     * already have a live Paseo workspace (`openWorkspaceId`) — reuse it
     * directly. If one doesn't, `worktree.open` resolves or creates one for
     * that checkout path. Always on whatever machine the Git view is
     * currently scoped to (filesMachineId).
     */
    fun openWorktreeInCode(worktree: WorktreeInfo, onOpened: () -> Unit) {
        val label = worktree.branch ?: worktree.path.substringAfterLast('/')
        val existing = worktree.openWorkspaceId
        if (existing != null) {
            openWorkspaceInFiles(existing, label, filesMachineId)
            onOpened()
            return
        }
        send(
            "worktree.open",
            JSONObject().put("machineId", filesMachineId).put("path", worktree.path),
            onResult = { result ->
                val workspaceId = if (result.isNull("workspaceId")) null else result.optString("workspaceId")
                if (workspaceId != null) openWorkspaceInFiles(workspaceId, label, filesMachineId)
                onOpened()
            },
            onError = { onOpened() },
        )
    }

    /**
     * Removes a worktree's checkout (spec §57 — destructive, caller must have
     * already shown a confirmation card). Only works for a worktree that's
     * currently open (has a live workspace_id) — matches what the Git view
     * can already show/act on.
     */
    fun removeWorktree(worktree: WorktreeInfo, onDone: () -> Unit, onError: (String) -> Unit) {
        val workspaceId = worktree.openWorkspaceId
        if (workspaceId == null) {
            onError("That worktree isn't currently open.")
            return
        }
        send(
            "worktree.remove",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", workspaceId),
            onResult = {
                refreshWorktrees()
                onDone()
            },
            onError = { onError("Could not remove that worktree.") },
        )
    }

    /**
     * Per-hunk accept/reject (spec §31). `action` is "stage" (adds only this
     * hunk to the index) or "discard" — discard is destructive (spec §57) and
     * the caller must only invoke it after its own confirmation card, same as
     * deleteBranch/removeWorktree below.
     */
    fun applyHunk(path: String, patch: String, action: String, onDone: () -> Unit, onError: (String) -> Unit) {
        send(
            "git.hunk.apply",
            JSONObject()
                .put("machineId", filesMachineId)
                .put("workspaceId", filesWorkspaceId)
                .put("path", path)
                .put("patch", patch)
                .put("action", action),
            onResult = { onDone() },
            onError = { error -> onError(error.optString("message", "Could not $action that hunk.")) },
        )
    }

    /** Destructive (spec §57) — only ever called after the caller's own confirmation card. */
    fun deleteBranch(branch: String, force: Boolean = false, onDone: () -> Unit, onError: (String) -> Unit) {
        send(
            "git.branch.delete",
            JSONObject().put("machineId", filesMachineId).put("workspaceId", filesWorkspaceId).put("branch", branch).put("force", force),
            onResult = {
                refreshBranches()
                onDone()
            },
            onError = { error -> onError(error.optString("message", "Could not delete $branch.")) },
        )
    }

    private fun applyWorktrees(result: JSONObject) {
        val arr = result.optJSONArray("worktrees") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            WorktreeInfo(
                path = w.optString("path"),
                branch = if (w.isNull("branch")) null else w.optString("branch"),
                openWorkspaceId = if (w.isNull("openWorkspaceId")) null else w.optString("openWorkspaceId"),
            )
        }
        _worktrees.value = list
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("LensCodeBridge", "onOpen: socket open, sending hello")
            sendHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            if (msg.optString("type") == "event") {
                handleEvent(msg)
                return
            }
            val id = if (msg.isNull("id")) null else msg.optString("id")
            if (id == null) return
            val result = msg.optJSONObject("result")
            try {
                if (result != null) {
                    errorCallbacks.remove(id)
                    callbacks.remove(id)?.invoke(result)
                } else {
                    callbacks.remove(id)
                    errorCallbacks.remove(id)?.invoke(msg.optJSONObject("error") ?: JSONObject())
                }
            } catch (e: Exception) {
                // A malformed/unexpected response must never take down the reader
                // loop — that would silently kill the whole connection.
                Log.e("LensCodeBridge", "callback for $id threw", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("LensCodeBridge", "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}", t)
            _state.value = _state.value.copy(bridgeConnected = false)
            if (closedByUs) return
            // Spec §59: never assume backend processes stopped; retry and resync.
            scope.launch {
                delay(3000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("LensCodeBridge", "onClosed: code=$code reason=$reason")
            _state.value = _state.value.copy(bridgeConnected = false)
        }
    }

    private fun applySnapshot(result: JSONObject) {
        // docs/paseo-migration-plan.md, Phase 1: the snapshot's `backend` key
        // carries {terminals, projects} rather than Herdr's {workspaces, panes,
        // agents} — "workspaces" here counts distinct projects instead.
        val backend = result.optJSONObject("backend")
        _state.value = _state.value.copy(
            workspaces = backend?.optJSONObject("projects")?.optJSONArray("projects")?.length() ?: 0,
            panes = backend?.optJSONArray("terminals")?.length() ?: 0,
            agents = agents.value.size,
        )
    }

    private fun handleEvent(msg: JSONObject) {
        if (msg.optString("topic") != "backend") return
        _state.value = _state.value.copy(lastEvent = msg.optString("event", "—"))
        send("snapshot.request", onResult = { snapshotResult -> applySnapshot(snapshotResult) })
        refreshTerminals()
        refreshAgents()
        refreshPermissions()
        trackedAgentId?.let { refreshAgentTimeline(it) }
    }
}
