package com.lenscode.app.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


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

    /** Read/written on both OkHttp's reader thread and the caller's — must be volatile. */
    @Volatile
    private var ws: WebSocket? = null
    private val nextId = AtomicLong(1)
    @Volatile
    private var closedByUs = false

    /** One in-flight request: its response callbacks plus the job that fails it on timeout. */
    private class PendingRequest(
        val onResult: ((JSONObject) -> Unit)?,
        val onError: ((JSONObject) -> Unit)?,
        val timeout: Job,
    )

    // OkHttp's listener thread and send() callers both mutate this — needs a
    // thread-safe map, not mutableMapOf.
    private val pending = ConcurrentHashMap<String, PendingRequest>()

    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var resyncJob: Job? = null

    fun connect() {
        closedByUs = false
        reconnectJob?.cancel()
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener())
    }

    fun close() {
        closedByUs = true
        reconnectJob?.cancel()
        resyncJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        // We're shutting down — don't surface "connection lost" errors to a UI
        // that is going away; just cancel the outstanding timeouts.
        pending.values.forEach { it.timeout.cancel() }
        pending.clear()
    }

    private fun scheduleReconnect() {
        if (closedByUs || reconnectJob?.isActive == true) return
        val attempt = reconnectAttempt++
        // Exponential backoff capped at 30s (spec §59): a dead bridge shouldn't
        // be hammered, and a recovering one shouldn't leave the client waiting.
        val delayMs = minOf(RECONNECT_BASE_MS shl minOf(attempt, 10), RECONNECT_MAX_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            Log.d("LensCodeBridge", "reconnect attempt ${attempt + 1} after ${delayMs}ms")
            connect()
        }
    }

    /** Fails every in-flight request — the socket that would answer them is gone. */
    private fun failAllPending(error: JSONObject) {
        val drained = pending.values.toList()
        pending.clear()
        for (req in drained) {
            req.timeout.cancel()
            try {
                req.onError?.invoke(error)
            } catch (e: Exception) {
                Log.e("LensCodeBridge", "error callback threw during disconnect", e)
            }
        }
    }

    private fun errorBody(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message).put("retryable", true)

    private fun requestTimeout(id: String): Job = scope.launch {
        delay(REQUEST_TIMEOUT_MS)
        val req = pending.remove(id) ?: return@launch
        Log.w("LensCodeBridge", "request $id timed out after ${REQUEST_TIMEOUT_MS}ms")
        try {
            req.onError?.invoke(errorBody("TIMEOUT", "The bridge did not respond in time."))
        } catch (e: Exception) {
            Log.e("LensCodeBridge", "timeout callback for $id threw", e)
        }
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
                // A completed handshake means the connection is healthy — reset
                // the backoff so the next drop retries quickly, not at the cap.
                reconnectAttempt = 0
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
        val socket = ws
        if (socket == null) {
            // No socket — this request can never be answered. Fail fast rather
            // than silently dropping it and leaving a pending entry to leak.
            onError?.invoke(errorBody("UNAVAILABLE", "Not connected to lenscode-bridge."))
            return
        }
        val id = "a${nextId.getAndIncrement()}"
        fields.put("id", id)
        fields.put("type", type)
        if (onResult != null || onError != null) {
            pending[id] = PendingRequest(onResult, onError, requestTimeout(id))
        }
        if (!socket.send(fields.toString())) {
            // OkHttp rejected the frame (queue full or socket shutting down).
            pending.remove(id)?.timeout?.cancel()
            onError?.invoke(errorBody("UNAVAILABLE", "Could not send to lenscode-bridge."))
        }
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
                _terminals.value = decodeList<PaneInfo>(result, "terminals").map { p ->
                    p.copy(machineId = machineId, title = p.title.ifBlank { p.terminalId })
                }
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
                _dirPath.value = path
                _dirEntries.value = decodeList<DirEntry>(result, "entries")
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
                val f = decodeResult<FileContent>(result)
                _openFile.value = if (f.path.isEmpty()) f.copy(path = path) else f
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
                _voiceResult.value = decodeResult<VoiceResolution>(result)
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
                _searchResults.value = decodeList<FileSearchHit>(result, "hits")
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
                _gitStatus.value = decodeResult<GitStatus>(result)
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
                _providers.value = decodeList<ProviderInfo>(result, "providers")
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
            val list = decodeList<AgentInfo>(result, "agents")
            list.forEach { agentMachines[it.agentId] = it.machineId }
            _agents.value = list
        })
    }


    /** First page of agent history (docs/snip-and-agent-management-plan.md) — replaces whatever was there before. */
    fun refreshAgentHistory(search: String? = null) {
        agentHistoryCursor = null
        val fields = JSONObject()
        if (!search.isNullOrBlank()) fields.put("search", search)
        send(
            "agent.history",
            fields,
            onResult = { result ->
                _agentHistory.value = decodeList<AgentHistoryEntry>(result, "entries")
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
                _agentHistory.value = _agentHistory.value + decodeList<AgentHistoryEntry>(result, "entries")
                agentHistoryCursor = if (result.isNull("nextCursor")) null else result.optString("nextCursor")
                _agentHistoryHasMore.value = result.optBoolean("hasMore", false)
            },
        )
    }

    /** Decodes one agent payload and indexes its machine for [withMachine] lookups. */
    private fun decodeAgent(json: JSONObject): AgentInfo =
        decodeResult<AgentInfo>(json).also { agentMachines[it.agentId] = it.machineId }

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
                _agentTimeline.value = decodeList<AgentTimelineEntry>(result, "entries")
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
                onCreated(decodeAgent(result.getJSONObject("agent")))
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
            _permissions.value = decodeList<PermissionInfo>(result, "permissions")
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
            _machines.value = decodeList<MachineInfo>(result, "machines")
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
    // RemoteBrowseEntry/RemoteBrowseState live in ProtocolModels.kt.

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
                val entries = decodeList<RemoteBrowseEntry>(result, "entries")
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
        _worktrees.value = decodeList<WorktreeInfo>(result, "worktrees")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("LensCodeBridge", "onOpen: socket open, sending hello")
            sendHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: JSONException) {
                // A malformed frame must not propagate — OkHttp would surface it
                // as onFailure and force a needless full reconnect/resync.
                Log.e("LensCodeBridge", "dropping malformed frame", e)
                return
            }
            if (msg.optString("type") == "event") {
                handleEvent(msg)
                return
            }
            val id = if (msg.isNull("id")) null else msg.optString("id")
            if (id == null) return
            val req = pending.remove(id) ?: return
            req.timeout.cancel()
            try {
                val result = msg.optJSONObject("result")
                if (result != null) {
                    req.onResult?.invoke(result)
                } else {
                    req.onError?.invoke(msg.optJSONObject("error") ?: JSONObject())
                }
            } catch (e: Exception) {
                // A malformed/unexpected response must never take down the reader
                // loop — that would silently kill the whole connection.
                Log.e("LensCodeBridge", "callback for $id threw", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("LensCodeBridge", "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}", t)
            // Ignore events from a stale socket already replaced by a reconnect —
            // it must not clear state or schedule another reconnect for the live one.
            if (webSocket !== ws) return
            ws = null
            _state.value = _state.value.copy(bridgeConnected = false, backendConnected = false)
            // In-flight requests over this socket will never be answered.
            failAllPending(errorBody("CONNECTION_LOST", "Lost the connection to lenscode-bridge."))
            // Spec §59: never assume backend processes stopped; retry and resync.
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("LensCodeBridge", "onClosed: code=$code reason=$reason")
            if (webSocket !== ws) return
            ws = null
            _state.value = _state.value.copy(bridgeConnected = false, backendConnected = false)
            failAllPending(errorBody("CONNECTION_LOST", "The bridge closed the connection."))
            // A graceful server-initiated close is still a disconnect — reconnect
            // unless we were the ones who closed it.
            scheduleReconnect()
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
        // Backend events arrive in bursts (one user action → several events).
        // Coalesce them into a single resync instead of a snapshot + 3 refreshes
        // per event.
        resyncJob?.cancel()
        resyncJob = scope.launch {
            delay(RESYNC_DEBOUNCE_MS)
            send("snapshot.request", onResult = { snapshotResult -> applySnapshot(snapshotResult) })
            refreshTerminals()
            refreshAgents()
            refreshPermissions()
            trackedAgentId?.let { refreshAgentTimeline(it) }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val RESYNC_DEBOUNCE_MS = 150L
    }
}
