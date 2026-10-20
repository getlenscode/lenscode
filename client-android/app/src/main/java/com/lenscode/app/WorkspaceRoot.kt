package com.lenscode.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.lenscode.app.ui.glassFinish
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.FollowingSubspace
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.FollowBehavior
import androidx.xr.compose.subspace.FollowTarget
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.padding
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.scene
import com.lenscode.app.net.BridgeClient
import com.lenscode.app.net.PaneInfo
import com.lenscode.app.ui.AgentChipContent
import com.lenscode.app.ui.AgentsPanelContent
import com.lenscode.app.ui.DiffPanelContent
import com.lenscode.app.ui.DockButtonContent
import com.lenscode.app.ui.agentChipSurface
import com.lenscode.app.ui.lensShape
import com.lenscode.app.ui.FilesPanelContent
import com.lenscode.app.ui.LensCodeColors
import com.lenscode.app.ui.LensCodeCorners
import com.lenscode.app.ui.PermissionsPanelContent
import com.lenscode.app.ui.PrimaryPanelContent
import com.lenscode.app.ui.RemoteBrowseOverlay
import com.lenscode.app.ui.RemotePanelContent
import com.lenscode.app.ui.SnipCropOverlay
import com.lenscode.app.ui.SnipRegion
import com.lenscode.app.ui.TerminalPanelContent
import com.lenscode.app.ui.VoiceOverlay
import com.lenscode.app.ui.rememberSnipCaptureController
import com.lenscode.app.voice.VoiceRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * Holds one panel's live layout state — separated from the modifier itself so
 * callers that want pin/depth controls (currently just the primary panel) can
 * read/mutate it without re-deriving the SubspaceModifier chain by hand.
 */
class PanelLayoutState(
    private val layoutStore: com.lenscode.app.layout.LayoutStore,
    private val id: String,
    offset: com.lenscode.app.layout.SavedOffset,
    size: com.lenscode.app.layout.SavedSize,
    pinned: Boolean,
) {
    var offset by mutableStateOf(offset)
        private set
    var size by mutableStateOf(size)
        private set
    var pinned by mutableStateOf(pinned)
        private set

    fun onMoveEnd(t: com.lenscode.app.layout.SavedOffset) {
        offset = t
        layoutStore.saveOffset(id, t)
    }

    fun onResizeEnd(s: com.lenscode.app.layout.SavedSize) {
        size = s
        layoutStore.saveSize(id, s)
    }

    fun togglePinned() {
        pinned = !pinned
        layoutStore.savePinned(id, pinned)
    }

    /** Bring-closer / push-farther (spec §52) — nudges depth (Z) by a fixed step. */
    fun nudgeDepth(deltaZ: Float) {
        val next = offset.copy(z = offset.z + deltaZ)
        offset = next
        layoutStore.saveOffset(id, next)
    }

    fun toModifier(padding: androidx.compose.ui.unit.Dp): SubspaceModifier = SubspaceModifier
        .width(size.width.dp)
        .height(size.height.dp)
        .padding(padding)
        .offset(offset.x.dp, offset.y.dp, offset.z.dp)
        .movable(enabled = !pinned, onMoveEnd = { event ->
            val t = event.pose.translation
            onMoveEnd(com.lenscode.app.layout.SavedOffset(t.x, t.y, t.z))
        })
        .resizable(enabled = !pinned, onResizeEnd = { newSize ->
            onResizeEnd(com.lenscode.app.layout.SavedSize(newSize.width.toFloat(), newSize.height.toFloat()))
            true
        })
}

@Composable
private fun rememberPanelLayoutState(
    layoutStore: com.lenscode.app.layout.LayoutStore,
    id: String,
    defaultWidth: Float,
    defaultHeight: Float,
    generation: Int = 0,
): PanelLayoutState = remember(id, generation) {
    PanelLayoutState(
        layoutStore,
        id,
        // Always launch centered at a readable size. Transforms remain live
        // for this session, but old scattered layouts are not restored.
        com.lenscode.app.layout.SavedOffset(0f, 0f, 0f),
        com.lenscode.app.layout.SavedSize(defaultWidth, defaultHeight),
        layoutStore.loadPinned(id),
    )
}

/**
 * Ephemeral UI state for [LensCodeWorkspace] — dialog/picker/voice/browser
 * flags that live and die with the composition. Grouped here so the root
 * composable reads as wiring + layout rather than ~40 loose `remember` vars.
 * Durable state lives in [com.lenscode.app.net.BridgeClient]'s StateFlows.
 */
@Stable
internal class WorkspaceUiState(
    initialBrowserUrl: String = "",
    initialThemeId: com.lenscode.app.ui.ThemeId,
    initialEditorFontSize: Int = com.lenscode.app.ui.EditorFontSize.DEFAULT,
    initialBridgeAddress: String = "",
) {
    var layoutGeneration by mutableStateOf(0)
    var activeView by mutableStateOf("home")
    /** Code editor full screen: hides the dock and composer. Only honored while activeView == "code". */
    var codeFullscreen by mutableStateOf(false)
    var selectedPane by mutableStateOf<PaneInfo?>(null)
    var stopConfirm by mutableStateOf<com.lenscode.app.ui.StopConfirmState?>(null)
    var branchConflict by mutableStateOf<com.lenscode.app.ui.BranchConflictState?>(null)
    var deleteConfirm by mutableStateOf<com.lenscode.app.ui.DeleteConfirmState?>(null)
    var diffDiscardConfirm by mutableStateOf<com.lenscode.app.ui.DeleteConfirmState?>(null)
    var fileSearchQuery by mutableStateOf("")
    var snipBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    var snipUploading by mutableStateOf(false)
    // Set once the crop is uploaded, while the user is being asked which of
    // several active agents to attach it to — cleared on pick or dismiss.
    var snipPendingMessage by mutableStateOf<String?>(null)
    var browserOverlayOpen by mutableStateOf(false)
    var browserUrl by mutableStateOf(initialBrowserUrl)
    var currentThemeId by mutableStateOf(initialThemeId)
    /** Code editor font size in sp; changed by voice (VoiceAction.SetFontSize), persisted by EditorPrefsStore. */
    var editorFontSize by mutableStateOf(initialEditorFontSize)
    /** Settings → Bridge connection: the address field and its validation message. */
    var bridgeAddressInput by mutableStateOf(initialBridgeAddress)
    var bridgeAddressError by mutableStateOf<String?>(null)
    // "Follow me" — Jetpack XR's app-side equivalent of the pin-to-headset
    // button a Home Space window gets from the OS; this Full Space app renders
    // its own panels instead of a system-managed window, so it never gets
    // that automatically. Off by default: it re-poses the *entire* subspace
    // root as one rigid block on every head move, fighting manual
    // reposition/pin/resize — an opt-in for walking, not the default.
    var followMeEnabled by mutableStateOf(false)
    // Metres in front of your face Follow Me holds the layout (tunable via the debug broadcast).
    var followMeDistanceM by mutableStateOf(0.6f)
    var agentDetail by mutableStateOf<com.lenscode.app.ui.AgentDetailState?>(null)
    var agentPromptText by mutableStateOf("")
    // The one agent voice's "ask_agent" action talks to — like one running
    // chat with Claude/Codex in a terminal, not a fresh agent per utterance.
    // Set the first time ask_agent has to create an agent; every ask_agent
    // after that (with no agent card explicitly open) sends into this same
    // agent instead of spinning up another one.
    var voiceAgentId by mutableStateOf<String?>(null)
    // The tmux-bridged terminal pane ask_agent's server-under-test runs
    // through (docs/paseo-migration-plan.md follow-up) — created once
    // alongside the first voice agent, then reused. See AskAgent's handler
    // for why: without this, agent Bash tool calls are invisible outside
    // querying Paseo directly, and interrupting one kills the process
    // outright rather than leaving it running the way a real terminal would.
    var voiceAgentTerminalId by mutableStateOf<String?>(null)
    var pairingDeviceName by mutableStateOf("LensCode XR Glasses")
    var pairingCode by mutableStateOf("")
    var isListening by mutableStateOf(false)
    /** Open dictation session for composer/agentPrompt modes — non-null means
     * the mic stays open until the user taps it again. Not observed by Compose;
     * stopping it doesn't need a recompose. */
    var dictationSession: com.lenscode.app.voice.VoiceRecognizer.DictationSession? = null
    var voicePartial by mutableStateOf("")
    var voiceError by mutableStateOf<String?>(null)
    var voiceMode by mutableStateOf("fileSearch")
    var pendingVoiceMode by mutableStateOf("fileSearch")
    // Final transcript from the global voice-command mic, awaiting Submit/Cancel
    // in VoiceCommandOverlay — see dispatchVoiceCommand below for what Submit does.
    var voiceCommandReview by mutableStateOf<String?>(null)
    var composerSendRequested by mutableStateOf(false)
    var weatherReading by mutableStateOf<com.lenscode.app.net.WeatherReading?>(null)
    var weatherLocationDenied by mutableStateOf(false)
    // Bumped by the Settings panel's Reload button to force geminiConfigResult
    // (and koogVoiceRouter below) to re-read lenscode-config.json — see
    // net/GeminiConfigStore.kt and ui/SettingsPanelContent.kt.
    var geminiConfigReloadKey by mutableStateOf(0)
    var isRoutingVoiceCommand by mutableStateOf(false)
    // ArDevice (Follow Me's head pose) needs Config.DeviceTrackingMode.SPATIAL enabled on
    // the session — DISABLED by default (confirmed live: constructing it threw
    // IllegalStateException before this). SPATIAL also needs the HEAD_TRACKING
    // runtime permission on immersive devices (developer.android.com/develop/
    // xr/jetpack-xr-sdk/arcore/device-pose) — request that first, then
    // configure once permission is granted. Follow Me below only ever
    // fires once this has actually succeeded, so there's no race with the toggle.
    var deviceTrackingReady by mutableStateOf(false)
    // Bumped after a permission grant to retry configure() — LaunchedEffect
    // below is keyed on this too, since granting permission doesn't otherwise
    // change xrSession's identity and so wouldn't trigger a re-run on its own.
    var headTrackingPermissionAttempt by mutableStateOf(0)
    var stagedHunks by mutableStateOf<Set<Int>>(emptySet())
    var snipPickerAgents by mutableStateOf<List<com.lenscode.app.net.AgentInfo>>(emptyList())
    var archivePickerAgents by mutableStateOf<List<com.lenscode.app.net.AgentInfo>>(emptyList())
    var openAgentPickerAgents by mutableStateOf<List<com.lenscode.app.net.AgentInfo>>(emptyList())
}

/**
 * Spatial layout mirroring the mockup (spec §71): agent strip on top, one
 * dominant primary panel, quick dock underneath.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
fun LensCodeWorkspace(bridge: BridgeClient, onEnterPip: () -> Unit = {}, onEnterFullSpace: (() -> Unit)? = null) {
    val state by bridge.state.collectAsState()
    val bridgeGemini by bridge.geminiStatus.collectAsState()
    val certPrompt by bridge.certPrompt.collectAsState()
    val terminals by bridge.terminals.collectAsState()
    val terminalOutput by bridge.terminalOutput.collectAsState()
    val dirPath by bridge.dirPath.collectAsState()
    val dirEntries by bridge.dirEntries.collectAsState()
    val filesRootLabel by bridge.filesRootLabel.collectAsState()
    val filesMachineIdFlow by bridge.filesMachineIdFlow.collectAsState()
    val projectRootPath by bridge.projectRootPath.collectAsState()
    val openFile by bridge.openFile.collectAsState()
    val agents by bridge.agents.collectAsState()
    val agentHistory by bridge.agentHistory.collectAsState()
    val agentHistoryHasMore by bridge.agentHistoryHasMore.collectAsState()
    val agentTimeline by bridge.agentTimeline.collectAsState()
    val permissions by bridge.permissions.collectAsState()
    val worktrees by bridge.worktrees.collectAsState()
    val machines by bridge.machines.collectAsState()
    val connectingMachineId by bridge.connectingMachineId.collectAsState()
    val machineConnectError by bridge.machineConnectError.collectAsState()
    val terminalsMachineId by bridge.terminalsMachineId.collectAsState()
    val remoteBrowse by bridge.remoteBrowse.collectAsState()

    // ── Panel layout persistence (spec §52) ─────────────────────────────
    val layoutStore = com.lenscode.app.layout.rememberLayoutStore()
    val context = LocalContext.current
    val browserUrlStore = remember { com.lenscode.app.net.BrowserUrlStore(context) }
    val themeStore = remember { com.lenscode.app.net.ThemeStore(context) }
    val editorPrefs = remember { com.lenscode.app.net.EditorPrefsStore(context) }
    // Ephemeral UI state — see WorkspaceUiState below.
    val ui = remember {
        WorkspaceUiState(
            browserUrlStore.load() ?: "",
            themeStore.load(),
            editorPrefs.loadFontSize(),
            bridge.state.value.bridgeUrl,
        )
    }
    fun resetLayout() {
        layoutStore.resetAll()
        ui.layoutGeneration++ // re-center the main panel and its attached rows
    }
    val gitStatus by bridge.gitStatus.collectAsState()
    val gitDiffPath by bridge.gitDiffPath.collectAsState()
    val gitDiffText by bridge.gitDiffText.collectAsState()
    val branches by bridge.branches.collectAsState()
    val currentBranch by bridge.currentBranch.collectAsState()
    val providers by bridge.providers.collectAsState()
    val voiceResult by bridge.voiceResult.collectAsState()
    val fileSearchResults by bridge.searchResults.collectAsState()

    // ── Snip tool (screenshot → crop → attach to the tmux voice agent) ────
    val snipController = rememberSnipCaptureController()

    // Voice archive_agent's "which one did you mean" picker — set only when
    // a named target fuzzy-matches more than one active agent.
    // Same, for voice open_agent.
    val snipScope = rememberCoroutineScope()
    fun showToast(message: String) {
        // Bridge callbacks arrive on the network thread, and Toast throws off
        // the main looper — which silently aborted whatever ran after it.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Browser dock button ──────────────────────────────────────────────
    // No embedded live preview in Code (docs/remote-setup-and-limits.md), so
    // this is the app's only way to see a change actually running — it opens
    // Chrome as a separate Android activity rather than anything drawn inside
    // this app's own panel. In XR that surfaces as its own spatial window in
    // Home Space; positioning it beside this panel is the user's/XR shell's
    // job, not something startActivity lets the launching app dictate.

    // ── App theme (Settings → App Theme) ─────────────────────────────────
    // Applied once at launch with whatever was persisted; onThemeChange below
    // re-applies (and re-persists) on every selection after that.
    LaunchedEffect(Unit) { com.lenscode.app.ui.applyAppTheme(ui.currentThemeId) }

    // A bare "192.168.1.x:3000" is the common case (a LAN dev server, almost
    // never with a cert for its raw IP) — default those to http. Everything
    // else (a real hostname like "youtube.com") defaults to https instead:
    // the app's network_security_config.xml only allow-lists cleartext for a
    // couple of dev hosts, so http to anything else is silently blocked
    // (ERR_CLEARTEXT_NOT_PERMITTED) before any connection is even attempted —
    // confirmed live, this is why "youtube.com" looked like "no internet".
    fun normalizeBrowserUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) return trimmed
        val host = trimmed.substringBefore('/').substringBefore(':')
        val looksLikeLan = host == "localhost" || host.endsWith(".local") ||
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        return if (looksLikeLan) "http://$trimmed" else "https://$trimmed"
    }

    // Renders in this app's own window (BrowserPanelContent's WebView) rather
    // than launching Chrome as a separate Android activity — a separate
    // Chrome window occludes/hides this app's other panels (Terminal in
    // particular) in Home Space instead of sitting beside them.
    fun openInAppBrowser(rawUrl: String) {
        val normalized = normalizeBrowserUrl(rawUrl) ?: return
        browserUrlStore.save(normalized)
        ui.browserUrl = normalized
        ui.browserOverlayOpen = false
        ui.activeView = "browser"
    }

    fun openInChrome(rawUrl: String) {
        val normalized = normalizeBrowserUrl(rawUrl) ?: return
        browserUrlStore.save(normalized)
        ui.browserUrl = normalized
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(normalized))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // Right-half launch bounds — the standard Android freeform-windowing
        // hint (setLaunchBounds), same mechanism Chromebooks/large-screen
        // Android use for "open this app docked to a side". Home Space's
        // multi-window layer honors it the same way any freeform host does;
        // it's a request the launched activity/window manager can override
        // (e.g. Chrome restoring its last size), not a placement guarantee —
        // there's no Android API that gives the launcher a hard guarantee
        // over another app's window.
        val metrics = context.resources.displayMetrics
        val bounds = android.graphics.Rect(metrics.widthPixels / 2, 0, metrics.widthPixels, metrics.heightPixels)
        val options = android.app.ActivityOptions.makeBasic().apply { launchBounds = bounds }
        try {
            context.startActivity(intent.setPackage("com.android.chrome"), options.toBundle())
        } catch (e: android.content.ActivityNotFoundException) {
            // Chrome specifically isn't installed — fall back to whatever the
            // system's default handler for http(s) is, rather than failing.
            try {
                context.startActivity(intent.setPackage(null), options.toBundle())
            } catch (e2: android.content.ActivityNotFoundException) {
                android.util.Log.w("LensCodeBridge", "openInChrome: no app can handle $normalized", e2)
            }
        }
        ui.browserOverlayOpen = false
    }

    if (BuildConfig.DEBUG) {
        // adb shell am broadcast -a com.lenscode.app.DEBUG_FOLLOW_ME --ez on true --ef m 0.6
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    if (intent == null) return
                    if (intent.hasExtra("on")) ui.followMeEnabled = intent.getBooleanExtra("on", false)
                    if (intent.hasExtra("m")) ui.followMeDistanceM = intent.getFloatExtra("m", 0.6f)
                    android.util.Log.d("LensCodeFollowMe", "debug: on=$ui.followMeEnabled m=$ui.followMeDistanceM")
                }
            }
            context.registerReceiver(
                receiver,
                android.content.IntentFilter("com.lenscode.app.DEBUG_FOLLOW_ME"),
                android.content.Context.RECEIVER_EXPORTED,
            )
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
    // Mode / model / worktree for new agents — one shared state for the Code
    // composer's chips and voice's ask_agent (and its set_* actions), so what
    // you see on the chips is exactly what a spoken task will use.
    val composer = remember { com.lenscode.app.ui.ComposerState() }

    // ── Device pairing (spec §55) ────────────────────────────────────────
    val pairingState by bridge.pairingState.collectAsState()

    fun openAgentDetail(agent: com.lenscode.app.net.AgentInfo) {
        ui.agentDetail = com.lenscode.app.ui.AgentDetailState(agent)
        ui.agentPromptText = ""
    }

    /**
     * Opens the agent's own directory in Files + Terminal (docs/paseo-migration-plan.md,
     * Phase 3). Paseo agents aren't tied to a terminal pane the way Herdr's
     * were (see AgentInfo's doc), so this opens where the agent actually
     * works — its workspaceId if it has one, otherwise a fresh terminal at
     * its cwd — rather than a specific pane that may not exist.
     */
    fun openAgentWorkspace(agent: com.lenscode.app.net.AgentInfo) {
        val workspaceId = agent.workspaceId
        if (workspaceId != null) {
            bridge.openWorkspaceInFiles(workspaceId, agent.label, machineId = "local")
        }
        bridge.switchTerminalsMachine("local")
        ui.activeView = if (workspaceId != null) "code" else "terminal"
        if (workspaceId == null) bridge.createTerminal(name = agent.label, cwd = agent.cwd)
        ui.agentDetail = null
    }

    // ── Voice input (spec §13, §63) — shared recognizer, two destinations:
    // file search (global overlay) and, once an agent card is open, filling
    // its prompt box directly instead. Never auto-submits what it hears —
    // file search still goes through candidates/confidence, and a voice
    // agent-prompt lands in the text field for you to review and hit Send,
    // never fires straight into agent.prompt on its own.
    val voiceRecognizer = remember { VoiceRecognizer(context) }

    // Debug-only text injection — types a transcript into the same review
    // popup a real recognized phrase would land in (Submit/Cancel still
    // required; nothing is bypassed), for testing without speaking out loud
    // in a public place. Only registered in debug builds. Trigger with:
    //   adb shell am broadcast -a com.lenscode.app.INJECT_VOICE_COMMAND --es text "open diff"
    if (BuildConfig.DEBUG) {
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    val text = intent?.getStringExtra("text") ?: return
                    android.util.Log.d("LensCodeVoice", "injected transcript: \"$text\"")
                    ui.voiceMode = "command"
                    ui.voiceCommandReview = text
                }
            }
            val filter = android.content.IntentFilter("com.lenscode.app.INJECT_VOICE_COMMAND")
            // RECEIVER_EXPORTED, not _NOT_EXPORTED: `adb shell am broadcast` runs
            // as the shell UID, not system, and RECEIVER_NOT_EXPORTED silently
            // drops broadcasts from anything but the app itself or the system —
            // confirmed live, it blocked adb entirely with no error. Acceptable
            // for a debug-only feature that only ever populates the review card
            // (still gated behind the user's own Submit tap, nothing auto-fires).
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    // An open dictation session owns the mic — it must not outlive this screen.
    DisposableEffect(Unit) {
        onDispose { ui.dictationSession?.stop(); ui.dictationSession = null }
    }


    fun stopVoice() {
        ui.dictationSession?.stop()
        ui.dictationSession = null
        ui.isListening = false
        ui.voicePartial = ""
    }

    /**
     * Inserts dictation at the box's current selection — so pinch-dragging the
     * cursor mid-dictation decides where the next utterance lands, and the
     * cursor advances past each committed segment.
     */
    fun insertDictationAtCursor(v: TextFieldValue, s: String): TextFieldValue {
        val sel = v.selection
        val merged = v.text.substring(0, sel.min) + s + v.text.substring(sel.max)
        return TextFieldValue(merged, selection = TextRange(sel.min + s.length))
    }

    fun startListening(mode: String) {
        ui.voiceMode = mode
        ui.voiceError = null
        ui.voicePartial = ""
        ui.isListening = true
        if (mode == "fileSearch") bridge.clearVoiceResult()
        if (mode == "command") ui.voiceCommandReview = null

        if (mode == "composer" || mode == "agentPrompt") {
            // Dictation modes keep the mic open until the mic button is tapped
            // again — SpeechRecognizer's ~5s endpointing is worked around by
            // transparent restarts inside the session (see VoiceRecognizer).
            ui.dictationSession = voiceRecognizer.startDictation(
                onPartial = { text -> ui.voicePartial = text },
                onSegment = { text ->
                    android.util.Log.d("LensCodeVoice", "dictation segment ($mode): \"$text\"")
                    ui.voicePartial = ""
                    if (mode == "composer") {
                        // Lands in the box for review, never sends by itself —
                        // except the bare word "send", so a fully hands-free
                        // round trip is still possible.
                        val cleaned = text.trim().trimEnd('.', '!', '?').lowercase()
                        if (cleaned in setOf("send", "send it", "submit", "go")) {
                            ui.composerSendRequested = true
                        } else {
                            composer.prompt = insertDictationAtCursor(composer.prompt, text.trim() + " ")
                        }
                    } else {
                        // agentPrompt's field is a plain String — segments append at the end.
                        ui.agentPromptText = listOf(ui.agentPromptText.trim(), text.trim())
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    }
                },
                onError = { message ->
                    android.util.Log.d("LensCodeVoice", "dictation error ($mode): $message")
                    ui.dictationSession = null
                    ui.isListening = false
                    ui.voiceError = message
                },
            )
            return
        }

        voiceRecognizer.listen(
            onPartial = { text ->
                ui.voicePartial = text
                if (mode == "browserUrl") ui.browserUrl = com.lenscode.app.voice.normalizeSpokenAddress(text)
                if (mode == "bridgeAddress") {
                    ui.bridgeAddressInput = com.lenscode.app.voice.normalizeSpokenAddress(text)
                }
            },
            onResult = { text ->
                android.util.Log.d("LensCodeVoice", "recognized ($mode): \"$text\"")
                ui.isListening = false
                when (mode) {
                    "fileSearch" -> bridge.voiceSearch(text)
                    // An address, not a sentence — "one nine two dot one six
                    // eight..." needs the digit/symbol-word collapsing, not
                    // the raw transcript.
                    "browserUrl" -> ui.browserUrl = com.lenscode.app.voice.normalizeSpokenAddress(text)
                    "bridgeAddress" -> ui.bridgeAddressInput = com.lenscode.app.voice.normalizeSpokenAddress(text)
                    // Never dispatches straight from speech — always lands in
                    // the review card first so a misheard word can't fire a
                    // destructive command (spec §57's confirmation discipline,
                    // applied one step earlier than usual: before the command
                    // is even classified).
                    "command" -> ui.voiceCommandReview = text
                }
            },
            onError = { message ->
                android.util.Log.d("LensCodeVoice", "recognizer error ($mode): $message")
                ui.isListening = false
                ui.voiceError = message
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(ui.pendingVoiceMode) else ui.voiceError = "Microphone permission is required for voice input."
    }

    // ── Home view weather HUD ────────────────────────────────────────────
    val weatherScope = rememberCoroutineScope()
    fun fetchWeatherForLastKnownLocation() {
        val locationManager = context.getSystemService(android.location.LocationManager::class.java) ?: return
        // getLastKnownLocation can stay null indefinitely on a device that's
        // never taken a fix (confirmed live on this Aura unit) — actively
        // request a fresh one via getCurrentLocation (API 30+, no min-SDK
        // concern here) instead of just hoping a cached fix exists.
        val provider = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            android.util.Log.w("LensCodeWeather", "no enabled location provider")
            return
        }
        weatherScope.launch {
            try {
                // A single request often comes back null on the first try —
                // confirmed live on this Aura unit (no cached fix, and the
                // first cold GPS/network resolve can simply be too slow).
                // Retry a few times with a short gap rather than giving up
                // after one null.
                var location: android.location.Location? = null
                repeat(4) { attempt ->
                    if (location != null) return@repeat
                    location = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        locationManager.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc, onCancellation = null)
                        }
                    }
                    if (location == null && attempt < 3) delay(5000)
                }
                if (location != null) {
                    ui.weatherReading = com.lenscode.app.net.WeatherClient.fetchCurrent(location.latitude, location.longitude)
                    android.util.Log.d("LensCodeWeather", "fetched: $ui.weatherReading")
                } else {
                    android.util.Log.w("LensCodeWeather", "getCurrentLocation returned null after retries")
                }
            } catch (e: SecurityException) {
                android.util.Log.w("LensCodeWeather", "location fetch denied", e)
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchWeatherForLastKnownLocation() else ui.weatherLocationDenied = true
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) fetchWeatherForLastKnownLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun requestVoice(mode: String) {
        if (ui.isListening) {
            // A dictation session stays open until tapped again — the tap is
            // "done talking". Tapping a different mic switches to that mode.
            if (ui.dictationSession != null) {
                val sameMode = ui.voiceMode == mode
                stopVoice()
                if (sameMode) return
            } else {
                return // single-shot session ends on its own within seconds
            }
        }
        ui.pendingVoiceMode = mode
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening(mode) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun saveBridgeAddress() {
        when (
            val result = com.lenscode.app.net.normalizeBridgeAddress(
                ui.bridgeAddressInput,
                // Release builds forbid cleartext, so only wss:// can work there.
                secureOnly = !BuildConfig.DEBUG,
            )
        ) {
            is com.lenscode.app.net.BridgeAddressResult.Invalid -> ui.bridgeAddressError = result.message
            is com.lenscode.app.net.BridgeAddressResult.Ok -> {
                ui.bridgeAddressError = null
                ui.bridgeAddressInput = result.url
                bridge.setAddress(result.url)
                showToast("Connecting to ${result.url}…")
            }
        }
    }

    fun requestVoiceSearch() = requestVoice("fileSearch")
    fun requestVoiceCommand() = requestVoice("command")

    /**
     * Executes one classified action (docs/paseo-migration-plan.md follow-up
     * — the agentic Koog/Gemini rewrite of the original regex router).
     * Navigation, file open, and search act immediately — they're not
     * destructive. Everything else reuses the app's existing confirmation-card
     * types (StopConfirmState/DeleteConfirmState) rather than acting directly,
     * so a voice command carries exactly the same safety guarantee as its
     * equivalent tap (spec §57) — the transcript-review step answers "did it
     * hear me right", these cards answer "are you sure". The LLM only ever
     * classifies; it never bypasses this confirmation layer.
     */
    /**
     * Shared by ask_agent (with a task) and new_agent (without one) — both end up
     * creating the same tmux-backed agent, just with a different first prompt.
     */
    fun startVoiceAgent(
        instruction: String?,
        // One-shot overrides from a spoken "…in a new worktree using Opus" —
        // they apply to this agent only and never touch the composer's defaults.
        modeId: String? = null,
        provider: String? = null,
        model: String? = null,
        worktree: Boolean? = null,
    ) {
        // The agent runs where Code is pointed: this machine, or the connected
        // remote host whose folder is open (its workspaceId is that folder's path).
        val agentMachine = filesMachineIdFlow
        // There is no "local" machine in this app — every agent runs on a
        // registered host (your laptop included). Nothing picked yet → ask.
        if (agentMachine == "local") {
            ui.activeView = "code"
            composer.menu = "machine"
            showToast("Pick a machine first — tap the machine chip, or say its name.")
            return
        }
        val remote = true
        ui.activeView = "agents"
        val useProvider = provider ?: composer.provider
        val useModel = if (provider != null || model != null) model else composer.model
        val useMode = modeId ?: composer.modeId
        val useWorktree = worktree ?: composer.worktree
        android.util.Log.d("LensCodeVoice", "startVoiceAgent provider=$useProvider model=$useModel mode=$useMode worktree=$useWorktree")
        // A tmux session both the agent and the Terminal view share
        // (docs/paseo-migration-plan.md follow-up) — created once,
        // then just told about in every later prompt. See the
        // instruction text below for exactly what this buys.
        // terminalId == null → the shared terminal couldn't be created (Paseo's
        // terminal.create can fail independently of agents), so the agent is
        // started anyway, just without the tmux mirroring instructions.
        fun createAgentInTmux(terminalId: String?) {
            if (terminalId != null) bridge.inputTerminal(terminalId, "tmux new -A -s lenscode-agent\n")
            val task = if (instruction != null) "Task: $instruction" else "No task yet — say a brief hello and wait for the next instruction."
            val prompt = if (terminalId == null) task else """
                You have a tmux session named "lenscode-agent" available, mirrored live
                in this workspace's own Terminal view. Always run shell commands
                through it instead of your default tool execution — that's what
                makes output visible there, and what lets a long-running process
                (a dev server, a watcher, …) keep running independently of this
                conversation instead of dying with whatever tool call started it:
                  tmux send-keys -t lenscode-agent "<command>" Enter
                  tmux capture-pane -t lenscode-agent -p     # to read output back
                To stop something, send a real interrupt inside the session
                (tmux send-keys -t lenscode-agent C-c, or kill its pid) rather than
                canceling your own tool call.

                $task
            """.trimIndent()
            bridge.createAgent(
                provider = useProvider,
                model = useModel,
                worktree = useWorktree,
                // The workspace Code is showing (a worktree's, say) — not the bridge's own root.
                workspaceId = bridge.currentWorkspaceId.takeIf { it != "local" },
                machineId = agentMachine,
                cwd = if (remote) bridge.currentWorkspaceId else ".",
                prompt = prompt,
                // Without an explicit title, Paseo falls back to a prefix of
                // `prompt` itself — which for every voice-created agent starts
                // with the same tmux boilerplate paragraph above, not the
                // actual task. Confirmed live: this is why every one of these
                // agents showed up identically as "You have a tmux session
                // named \"lenscode-agent\" available, mirror…" everywhere the
                // agent's label is shown, permission cards included, hiding
                // which agent a pending permission was actually for.
                title = instruction ?: "Voice agent",
                modeId = useMode,
                onCreated = { created ->
                    ui.voiceAgentId = created.agentId
                    if (useWorktree) showToast("Started in a new worktree.")
                    android.util.Log.d("LensCodeVoice", "started agent ${created.agentId} (tmux)${instruction?.let { " for \"$it\"" } ?: ""}")
                },
                onError = { message ->
                    android.util.Log.w("LensCodeVoice", "agent.create failed: $message")
                    showToast("Couldn't start the agent: ${message.removePrefix("Error: ")}")
                },
            )
        }
        val terminalId = ui.voiceAgentTerminalId
        if (remote) {
            // The shared tmux terminal is a local convenience — skip it on a remote host.
            createAgentInTmux(null)
        } else if (terminalId != null) {
            createAgentInTmux(terminalId)
        } else {
            bridge.createTerminal(
                name = "lenscode-agent",
                onCreated = { newTerminalId ->
                    ui.voiceAgentTerminalId = newTerminalId
                    createAgentInTmux(newTerminalId)
                },
                onError = { error ->
                    val message = error.optString("message", "Couldn't start the agent's terminal.")
                    android.util.Log.w("LensCodeVoice", "terminal.create failed: $message (${error.optString("detail")})")
                    ui.voiceError = message
                    // Don't let a terminal failure block the agent itself.
                    showToast("Terminal unavailable — starting the agent without it.")
                    createAgentInTmux(null)
                },
            )
        }
    }

    // ── Snip tool ────────────────────────────────────────────────────────
    // "Snip" dock button: flatten the workspace into a screenshot, let the
    // user crop it (two-hand pinch on the corner handles, or one hand on
    // either), then hand it to whichever agent voice's ask_agent would talk
    // to — same "one running conversation" target, so a snip is just another
    // turn in that chat.
    fun startSnip() {
        snipScope.launch {
            val bitmap = snipController.captureComposite()
            if (bitmap == null) showToast("Nothing on screen to capture yet.") else ui.snipBitmap = bitmap
        }
    }

    // Opens an existing agent's card with the screenshot path sitting in the
    // prompt box, unsent — same "review before it goes anywhere" rule every
    // other voice-filled prompt in this app already follows, rather than
    // firing a bare "look at this" turn with no actual instruction attached.
    fun attachSnipToAgent(agent: com.lenscode.app.net.AgentInfo, message: String) {
        ui.voiceAgentId = agent.agentId
        openAgentDetail(agent)
        ui.agentPromptText = message
        ui.activeView = "agents"
    }

    /**
     * Fuzzy-resolves a spoken agent name against the live, non-closed agent
     * list and calls exactly one callback — the shared "never silently
     * guess" resolution both archive_agent and open_agent need when a name
     * is given instead of "current"/"all" (spec §13's discipline).
     */
    fun resolveAgentByName(
        name: String,
        onOne: (com.lenscode.app.net.AgentInfo) -> Unit,
        onMany: (List<com.lenscode.app.net.AgentInfo>) -> Unit,
        onNone: () -> Unit,
    ) {
        val matches = agents.filter { it.status != "closed" && it.label.contains(name, ignoreCase = true) }
        when {
            matches.isEmpty() -> onNone()
            matches.size == 1 -> onOne(matches[0])
            else -> onMany(matches)
        }
    }

    fun cropSnipBitmap(cropRect: androidx.compose.ui.geometry.Rect): android.graphics.Bitmap? {
        val source = ui.snipBitmap ?: return null
        val left = cropRect.left.toInt().coerceIn(0, source.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, source.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, source.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, source.height)
        return android.graphics.Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun saveSnipToGallery(cropRect: androidx.compose.ui.geometry.Rect) {
        val cropped = cropSnipBitmap(cropRect) ?: return
        ui.snipBitmap = null
        val filename = "lenscode-snip-${System.currentTimeMillis()}.png"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LensCode")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            showToast("Could not save screenshot to gallery.")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            showToast("Saved to gallery — Pictures/LensCode.")
        } catch (e: Exception) {
            android.util.Log.w("LensCodeSnip", "gallery save failed", e)
            showToast("Could not save screenshot to gallery.")
        }
    }

    fun confirmSnip(cropRect: androidx.compose.ui.geometry.Rect) {
        val cropped = cropSnipBitmap(cropRect) ?: return
        ui.snipBitmap = null
        ui.snipUploading = true
        val stream = java.io.ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        bridge.uploadSnip(
            base64,
            onResult = { path ->
                ui.snipUploading = false
                val message = "Screenshot attached: $path"
                // Never guess which of several running conversations a snip
                // belongs to (spec §13's "never silently guess" discipline) —
                // only auto-pick when there's exactly one candidate.
                val active = agents.filter { it.status != "closed" }
                when {
                    active.isEmpty() -> startVoiceAgent(instruction = message)
                    active.size == 1 -> attachSnipToAgent(active[0], message)
                    else -> {
                        ui.snipPendingMessage = message
                        ui.snipPickerAgents = active
                    }
                }
            },
            onError = {
                ui.snipUploading = false
                showToast("Could not upload that screenshot — check the bridge connection.")
            },
        )
    }

    // ── Code-view composer helpers (shared by taps and voice) ──────────────
    fun norm(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun sendComposer() {
        val text = composer.prompt.text.trim()
        if (text.isEmpty()) return
        composer.prompt = TextFieldValue("")
        composer.menu = null
        showToast("Starting agent…")
        startVoiceAgent(instruction = text)
    }
    LaunchedEffect(ui.composerSendRequested) {
        if (ui.composerSendRequested) {
            ui.composerSendRequested = false
            sendComposer()
        }
    }

    /** Maps a spoken/typed mode name to one of the composer's modes; null if it isn't a mode at all. */
    fun resolveMode(heard: String): com.lenscode.app.ui.ComposerMode? {
        val h = heard.lowercase()
        return when {
            "bypass" in h || "skip" in h -> com.lenscode.app.ui.ComposerBypassMode
            "plan" in h -> com.lenscode.app.ui.ComposerModes.first { it.id == "plan" }
            "manual" in h || "ask" in h || "default" in h -> com.lenscode.app.ui.ComposerModes.first { it.id == "default" }
            "accept" in h || "auto" in h || "edit" in h -> com.lenscode.app.ui.ComposerModes.first { it.id == "acceptEdits" }
            else -> null
        }
    }

    /** Fuzzy model match over every provider's models (and provider names): "opus" → the Opus model id. Speech rarely matches an id exactly. */
    fun resolveModel(heard: String): Pair<String, String?>? {
        val want = norm(heard)
        if (want.isEmpty()) return null
        for (p in providers.filter { it.available }) if (norm(p.provider) == want) return p.provider to null
        val hits = providers.filter { it.available }.flatMap { p -> p.models.map { p.provider to it } }
            .filter { norm(it.second).contains(want) }
        // Several versions of one family match ("opus") — take the shortest id,
        // which is the base/latest alias, and the confirmation toast names it.
        return hits.minByOrNull { it.second.length }
    }

    /** Applies a mode pick. Bypass always asks first — by tap or by voice ("say confirm"). */
    fun chooseMode(m: com.lenscode.app.ui.ComposerMode) {
        if (m.id == com.lenscode.app.ui.ComposerBypassMode.id && composer.modeId != m.id) {
            ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
                title = "Turn off all approvals?",
                message = "Bypass permissions lets agents run every tool call without asking. Say “confirm” or tap Enable.",
                confirmLabel = "Enable",
                onConfirm = {
                    composer.modeId = m.id
                    composer.flash = "mode"
                    ui.deleteConfirm = null
                    showToast("Mode → ${m.label}")
                },
                onCancel = { ui.deleteConfirm = null },
            )
        } else {
            composer.modeId = m.id
            composer.flash = "mode"
            showToast("Mode → ${m.label}")
        }
    }

    fun chooseModel(provider: String, model: String?) {
        composer.provider = provider
        composer.model = model
        composer.flash = "model"
        showToast("Model → ${model ?: provider}")
    }

    /** Branch switch with the dirty-tree resolution card (spec §27) — shared by the branch menu and voice. */
    fun switchBranchFlow(target: String) {
        fun opened(workspaceId: String?, machineId: String) {
            ui.branchConflict = null
            if (workspaceId != null) {
                bridge.openWorkspaceInFiles(workspaceId, target, machineId)
                ui.activeView = "code"
            }
        }
        fun attempt(onDirty: String?) {
            bridge.switchBranch(
                target = target,
                onDirty = onDirty,
                onSwitched = {
                    ui.branchConflict = null
                    composer.flash = "branch"
                    showToast("Branch → $target")
                },
                onWorktreeCreated = { workspaceId, machineId -> opened(workspaceId, machineId) },
                onConflict = {
                    if (onDirty != null) {
                        ui.branchConflict = null
                    } else {
                        ui.branchConflict = com.lenscode.app.ui.BranchConflictState(
                            target = target,
                            onStash = { attempt("stash") },
                            onWorktree = { attempt("worktree") },
                            onCancel = {
                                ui.branchConflict = null
                                attempt("abort")
                            },
                        )
                    }
                },
            )
        }
        attempt(null)
    }

    /**
     * Creates a worktree for [branch] and opens it in Code — shared by the
     * composer's "+ New worktree" picker and voice's create_worktree.
     */
    fun createWorktreeFlow(branch: String) {
        bridge.createWorktree(
            branch,
            onCreated = { workspaceId, machineId ->
                if (workspaceId != null) {
                    bridge.openWorkspaceInFiles(workspaceId, branch, machineId)
                    ui.activeView = "code"
                    showToast("Worktree → $branch")
                } else {
                    showToast("Worktree created for $branch.")
                }
            },
            onError = { msg -> showToast(msg) },
        )
    }

    /** §57 confirm card for worktree removal — shared by the composer's × and voice's remove_worktree. */
    fun confirmRemoveWorktree(wt: com.lenscode.app.net.WorktreeInfo) {
        ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
            title = "Remove worktree?",
            message = "This deletes the checkout at ${wt.path}. " +
                "Any uncommitted changes there are lost.",
            confirmLabel = "Remove",
            onConfirm = {
                bridge.removeWorktree(
                    wt,
                    onDone = {
                        ui.deleteConfirm = null
                        // If Code was rooted inside the removed checkout, its
                        // root is now dead — re-root rather than leaving the
                        // file browser on a ghost dir. Local goes back to the
                        // project root; remote has no "local" alias, so land
                        // on the worktree's parent folder.
                        if (wt.openWorkspaceId == bridge.currentWorkspaceId ||
                            wt.path == bridge.currentWorkspaceId
                        ) {
                            if (filesMachineIdFlow == "local") {
                                bridge.openWorkspaceInFiles("local", "local")
                            } else {
                                val parent = wt.path.substringBeforeLast('/', "/")
                                bridge.openRemoteFolderInFiles(
                                    filesMachineIdFlow,
                                    parent,
                                    parent.substringAfterLast('/'),
                                )
                            }
                        }
                        showToast("Worktree removed.")
                    },
                    onError = { msg ->
                        ui.deleteConfirm = null
                        showToast(msg)
                    },
                )
            },
            onCancel = { ui.deleteConfirm = null },
        )
    }

    /** Loose worktree match for speech — branch name, then checkout-folder name, ignoring punctuation and case. */
    fun resolveWorktree(heard: String): com.lenscode.app.net.WorktreeInfo? {
        val want = norm(heard)
        if (want.isEmpty()) return null
        fun key(wt: com.lenscode.app.net.WorktreeInfo) = norm(wt.branch ?: wt.path.substringAfterLast('/'))
        return worktrees.firstOrNull { key(it) == want }
            ?: worktrees.firstOrNull { key(it).startsWith(want) }
            ?: worktrees.firstOrNull { key(it).contains(want) }
    }

    /** Loose branch-name match for speech: exact, then prefix, then substring, ignoring punctuation and case. */
    fun resolveBranch(heard: String): String? {
        val want = norm(heard)
        if (want.isEmpty()) return null
        return branches.firstOrNull { norm(it) == want }
            ?: branches.firstOrNull { norm(it).startsWith(want) }
            ?: branches.firstOrNull { norm(it).contains(want) }
    }

    fun executeVoiceAction(action: com.lenscode.app.voice.VoiceAction) {
        android.util.Log.d("LensCodeVoice", "executing: $action")
        when (action) {
            is com.lenscode.app.voice.VoiceAction.Navigate -> {
                // The dock's own Browser button doesn't set ui.activeView directly —
                // it opens the URL launch dialog first (BrowserLaunchOverlay),
                // since there's no browser view to land on with nothing loaded.
                // Voice mirrors that instead of showing an empty Browser panel.
                when (action.view) {
                    "browser" -> ui.browserOverlayOpen = true
                    // The Git tab is gone — branches and worktrees live in the
                    // Code composer now. "open git" / "show worktrees" still work.
                    "git" -> {
                        ui.activeView = "code"
                        bridge.refreshBranches()
                        bridge.refreshWorktrees()
                        composer.menu = "branch"
                    }
                    else -> ui.activeView = action.view
                }
            }

            is com.lenscode.app.voice.VoiceAction.OpenFile -> {
                // Reuses the existing fuzzy-resolve + confidence flow
                // (bridge.voiceSearch → LaunchedEffect(voiceResult) below
                // opens a confident hit and switches to Code automatically).
                // `action.line` isn't wired to anything yet — the file viewer
                // has no scroll-to-line capability — so it's logged and
                // otherwise dropped rather than silently pretending to honor it.
                if (action.line != null) {
                    android.util.Log.d("LensCodeVoice", "open_file requested line ${action.line} — not supported yet, ignoring")
                }
                bridge.voiceSearch(action.path)
            }

            com.lenscode.app.voice.VoiceAction.AcceptChanges -> {
                val path = gitDiffPath
                val text = gitDiffText
                ui.activeView = "diff"
                if (path != null && text != null) {
                    val (header, hunks) = com.lenscode.app.ui.parseDiffHunks(text)
                    for (hunk in hunks) {
                        bridge.applyHunk(path, com.lenscode.app.ui.hunkPatch(header, hunk), "stage", onDone = {}, onError = {})
                    }
                }
            }

            com.lenscode.app.voice.VoiceAction.DiscardChanges -> {
                val path = gitDiffPath
                val text = gitDiffText
                ui.activeView = "diff"
                if (path != null && text != null) {
                    val (header, hunks) = com.lenscode.app.ui.parseDiffHunks(text)
                    ui.diffDiscardConfirm = com.lenscode.app.ui.DeleteConfirmState(
                        title = "Discard all changes in $path?",
                        message = "This reverts every uncommitted hunk in this file back to HEAD.",
                        onConfirm = {
                            for (hunk in hunks) {
                                bridge.applyHunk(path, com.lenscode.app.ui.hunkPatch(header, hunk), "discard", onDone = {}, onError = {})
                            }
                            ui.diffDiscardConfirm = null
                            bridge.loadGitDiff(path)
                        },
                        onCancel = { ui.diffDiscardConfirm = null },
                    )
                }
            }

            com.lenscode.app.voice.VoiceAction.NewTerminal -> {
                ui.activeView = "terminal"
                bridge.createTerminal()
            }

            com.lenscode.app.voice.VoiceAction.KillTerminal -> {
                ui.activeView = "terminal"
                val pane = ui.selectedPane
                if (pane != null) {
                    ui.stopConfirm = com.lenscode.app.ui.StopConfirmState(
                        pane = pane,
                        processLabel = "the running process",
                        onConfirm = {
                            bridge.interruptTerminal(pane.terminalId, pane.machineId)
                            ui.stopConfirm = null
                        },
                        onCancel = { ui.stopConfirm = null },
                    )
                } else {
                    // Confirmed live: this used to be a silent no-op with zero
                    // feedback when nothing was selected — "why didn't it do
                    // it" with no way to tell the command was even heard right.
                    showToast("No terminal is selected to interrupt.")
                }
            }

            is com.lenscode.app.voice.VoiceAction.CloseTerminal -> {
                // Not gated behind a confirm card — matches the existing
                // tap-to-close "×" button on a terminal row, which doesn't
                // confirm either (only interrupting a running process does).
                ui.activeView = "terminal"
                fun closeAll(panes: List<com.lenscode.app.net.PaneInfo>) {
                    for (pane in panes) {
                        bridge.closeTerminal(pane.terminalId, pane.machineId) {
                            if (ui.selectedPane?.terminalId == pane.terminalId) ui.selectedPane = null
                        }
                    }
                }
                val target = action.target.trim()
                when {
                    target.isBlank() || target.equals("current", ignoreCase = true) -> {
                        val pane = ui.selectedPane
                        if (pane != null) closeAll(listOf(pane)) else showToast("No terminal is selected to close.")
                    }
                    target.equals("all", ignoreCase = true) -> {
                        if (terminals.isEmpty()) showToast("No terminals to close.") else closeAll(terminals)
                    }
                    else -> {
                        val matches = terminals.filter {
                            it.title.contains(target, ignoreCase = true) || it.cwd.contains(target, ignoreCase = true)
                        }
                        if (matches.isEmpty()) showToast("No terminal matching \"$target\" found.") else closeAll(matches)
                    }
                }
            }

            is com.lenscode.app.voice.VoiceAction.ArchiveAgent -> {
                // Never guesses which agent a named target means (spec §13's
                // "never silently guess" discipline, same rule the snip
                // flow's multi-agent picker follows): exactly one active
                // match archives directly, several surface the picker,
                // zero/none-named falls back to whatever's open.
                fun confirmArchive(agent: com.lenscode.app.net.AgentInfo) {
                    ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
                        title = "Archive this agent?",
                        message = "This closes ${agent.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                        onConfirm = {
                            bridge.archiveAgent(agent.agentId)
                            if (ui.voiceAgentId == agent.agentId) ui.voiceAgentId = null
                            ui.deleteConfirm = null
                            if (ui.agentDetail?.agent?.agentId == agent.agentId) ui.agentDetail = null
                        },
                        onCancel = { ui.deleteConfirm = null },
                    )
                }
                val target = action.target.trim()
                when {
                    target.isBlank() || target.equals("current", ignoreCase = true) -> {
                        val agent = ui.agentDetail?.agent
                        if (agent != null) confirmArchive(agent) else showToast("No agent card is open to archive.")
                    }
                    target.equals("all", ignoreCase = true) -> {
                        val active = agents.filter { it.status != "closed" }
                        if (active.isEmpty()) {
                            showToast("No active agents to archive.")
                        } else {
                            ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
                                title = "Archive all ${active.size} agents?",
                                message = "This closes every running agent's runtime. Their history stays in Paseo.",
                                onConfirm = {
                                    for (agent in active) {
                                        bridge.archiveAgent(agent.agentId)
                                        if (ui.voiceAgentId == agent.agentId) ui.voiceAgentId = null
                                    }
                                    ui.deleteConfirm = null
                                    ui.agentDetail = null
                                },
                                onCancel = { ui.deleteConfirm = null },
                            )
                        }
                    }
                    else -> resolveAgentByName(
                        target,
                        onOne = { confirmArchive(it) },
                        onMany = { ui.archivePickerAgents = it },
                        onNone = { showToast("No active agent named \"$target\" found.") },
                    )
                }
            }

            is com.lenscode.app.voice.VoiceAction.OpenAgent -> {
                resolveAgentByName(
                    action.name,
                    onOne = { agent ->
                        openAgentDetail(agent)
                        ui.activeView = "agents"
                    },
                    onMany = { ui.openAgentPickerAgents = it },
                    onNone = { showToast("No active agent named \"${action.name}\" found.") },
                )
            }

            com.lenscode.app.voice.VoiceAction.Dismiss -> {
                // Priority: most specific/most-recently-opened overlay first —
                // in practice at most one of these is ever open at once, but
                // check in this order in case that ever changes. Each state's
                // own onCancel is called rather than just nulling the var
                // directly where one exists (ui.branchConflict's, in particular,
                // actively aborts an in-flight branch switch server-side —
                // just clearing the local state would leave that dangling).
                when {
                    composer.menu != null -> composer.menu = null
                    ui.archivePickerAgents.isNotEmpty() -> {
                        ui.archivePickerAgents = emptyList()
                        showToast("Canceled.")
                    }
                    ui.openAgentPickerAgents.isNotEmpty() -> {
                        ui.openAgentPickerAgents = emptyList()
                        showToast("Canceled.")
                    }
                    ui.snipPendingMessage != null -> {
                        ui.snipPendingMessage = null
                        ui.snipPickerAgents = emptyList()
                        showToast("Screenshot discarded — no agent selected.")
                    }
                    ui.snipBitmap != null -> ui.snipBitmap = null
                    ui.agentDetail != null -> ui.agentDetail = null
                    ui.deleteConfirm != null -> ui.deleteConfirm?.onCancel?.invoke()
                    ui.diffDiscardConfirm != null -> ui.diffDiscardConfirm?.onCancel?.invoke()
                    ui.branchConflict != null -> ui.branchConflict?.onCancel?.invoke()
                    ui.stopConfirm != null -> ui.stopConfirm?.onCancel?.invoke()
                    bridge.remoteBrowse.value != null -> bridge.closeRemoteBrowse()
                    ui.browserOverlayOpen -> ui.browserOverlayOpen = false
                    else -> showToast("Nothing to dismiss.")
                }
            }

            com.lenscode.app.voice.VoiceAction.NewAgent -> {
                // Only actually spawn a fresh one when there's nothing already
                // running — same "one voice agent conversation" model as
                // ask_agent below; otherwise just surface the existing one.
                val openTarget = ui.agentDetail?.agent?.agentId
                val reuseTarget = ui.voiceAgentId?.takeIf { id -> agents.any { it.agentId == id && it.status != "closed" } }
                val target = openTarget ?: reuseTarget
                if (target != null) {
                    ui.activeView = "agents"
                } else {
                    startVoiceAgent(instruction = null)
                }
            }

            is com.lenscode.app.voice.VoiceAction.Search -> bridge.voiceSearch(action.query)

            is com.lenscode.app.voice.VoiceAction.RespondPermission -> {
                // Prefer the pending permission for whichever agent's card is
                // open (matches what you're actually looking at); otherwise
                // the oldest pending one globally — same "first in line"
                // behavior a human tapping through the Permissions queue
                // top-to-bottom would get.
                val target = permissions.firstOrNull { it.agentId == ui.agentDetail?.agent?.agentId }
                    ?: permissions.firstOrNull()
                if (target != null) {
                    bridge.respondToPermission(target, action.behavior)
                } else {
                    android.util.Log.w("LensCodeVoice", "respond_permission: nothing pending to answer")
                }
            }

            is com.lenscode.app.voice.VoiceAction.AskAgent -> {
                // Priority: an explicitly open agent card wins (the user is
                // clearly looking at that one) — otherwise this is one running
                // conversation with a single agent across every voice call, the
                // same as typing turn after turn into one Claude/Codex session,
                // not a fresh agent per utterance. Reuse ui.voiceAgentId as long as
                // that agent is still alive; only create a new one the first
                // time, or after it's gone (archived/closed elsewhere).
                val openTarget = ui.agentDetail?.agent?.agentId
                val reuseTarget = ui.voiceAgentId?.takeIf { id -> agents.any { it.agentId == id && it.status != "closed" } }
                val target = openTarget ?: reuseTarget
                val wantsNewAgent = action.model != null || action.mode != null || action.worktree == true
                if (target != null && !wantsNewAgent) {
                    bridge.sendAgentMessage(target, action.instruction)
                } else {
                    // Settings named inside the sentence ("in a new worktree using
                    // Opus…") only make sense for a fresh agent, and only for this one.
                    val mode = action.mode?.let { resolveMode(it) }
                    val picked = action.model?.let { resolveModel(it) }
                    if (action.model != null && picked == null) showToast("No model matching \"${action.model}\" — using ${composer.model ?: composer.provider}.")
                    startVoiceAgent(
                        instruction = action.instruction,
                        modeId = mode?.id?.takeIf { it != com.lenscode.app.ui.ComposerBypassMode.id }, // never bypass approvals from a passing phrase
                        provider = picked?.first,
                        model = picked?.second,
                        worktree = action.worktree,
                    )
                }
            }

            is com.lenscode.app.voice.VoiceAction.SetMode -> {
                val m = resolveMode(action.mode)
                if (m == null) showToast("Not a mode I know: \"${action.mode}\". Try plan, accept edits, or manual.") else {
                    ui.activeView = "code"
                    chooseMode(m)
                }
            }

            is com.lenscode.app.voice.VoiceAction.SetModel -> {
                val picked = resolveModel(action.model)
                if (picked == null) {
                    ui.activeView = "code"
                    composer.menu = "model"
                    showToast("No model matching \"${action.model}\" — pick one, or say its name.")
                } else {
                    chooseModel(picked.first, picked.second)
                }
            }

            is com.lenscode.app.voice.VoiceAction.SetWorktree -> {
                composer.worktree = action.enabled
                composer.flash = "branch"
                showToast(if (action.enabled) "New agents will run in a new worktree." else "Worktree off — agents run in the current checkout.")
            }

            is com.lenscode.app.voice.VoiceAction.SwitchBranch -> {
                val match = resolveBranch(action.branch)
                if (match == null) {
                    ui.activeView = "code"
                    composer.menu = "branch"
                    showToast("No branch matching \"${action.branch}\" — pick one, or say its name.")
                } else if (match == currentBranch) {
                    showToast("Already on $match.")
                } else {
                    switchBranchFlow(match)
                }
            }

            is com.lenscode.app.voice.VoiceAction.CreateWorktree -> {
                val match = resolveBranch(action.branch)
                val existing = resolveWorktree(action.branch)
                when {
                    existing != null -> {
                        // Already has a worktree — creating would just redirect to it anyway.
                        bridge.openWorktreeInCode(existing) { ui.activeView = "code" }
                        showToast("${existing.branch ?: action.branch} already has a worktree — opening it.")
                    }
                    match == null -> {
                        ui.activeView = "code"
                        composer.menu = "newworktree"
                        showToast("No branch matching \"${action.branch}\" — pick one, or say its name.")
                    }
                    match == currentBranch -> {
                        showToast("$match is the current checkout — it can't also live in a worktree.")
                    }
                    else -> createWorktreeFlow(match)
                }
            }

            is com.lenscode.app.voice.VoiceAction.RemoveWorktree -> {
                val wt = resolveWorktree(action.branch)
                if (wt == null) {
                    ui.activeView = "code"
                    composer.menu = "branch"
                    showToast("No worktree matching \"${action.branch}\".")
                } else {
                    confirmRemoveWorktree(wt)
                }
            }

            is com.lenscode.app.voice.VoiceAction.SetFontSize -> {
                val next = com.lenscode.app.ui.EditorFontSize.resolve(ui.editorFontSize, action.size, action.change)
                ui.editorFontSize = next
                editorPrefs.saveFontSize(next)
                showToast("Editor font size: $next")
            }

            com.lenscode.app.voice.VoiceAction.ComposerStatus -> {
                val modeLabel = com.lenscode.app.ui.composerModeFor(composer.modeId).label
                showToast(
                    "Mode: $modeLabel · Model: ${composer.model ?: composer.provider} · " +
                        "Branch: ${currentBranch ?: "unknown"} · Worktree: ${if (composer.worktree) "on" else "off"}",
                )
            }

            com.lenscode.app.voice.VoiceAction.Confirm -> {
                val card = ui.deleteConfirm ?: ui.diffDiscardConfirm
                when {
                    card != null -> card.onConfirm()
                    permissions.isNotEmpty() -> executeVoiceAction(com.lenscode.app.voice.VoiceAction.RespondPermission("allow"))
                    else -> showToast("Nothing to confirm.")
                }
            }
        }
    }

    /** Runs a whole plan's actions in order — see [executeVoiceAction] for what each does and why it's safe to just run them. */
    fun executeVoicePlan(plan: com.lenscode.app.voice.VoiceCommandPlan) {
        for (action in plan.toActions()) executeVoiceAction(action)
    }

    /** Maps the old deterministic single-intent router's output onto the new action grammar, for the no-key/offline fallback path. */
    fun com.lenscode.app.voice.VoiceCommand.toAction(): com.lenscode.app.voice.VoiceAction = when (this) {
        is com.lenscode.app.voice.VoiceCommand.Navigate -> com.lenscode.app.voice.VoiceAction.Navigate(view)
        com.lenscode.app.voice.VoiceCommand.AcceptChanges -> com.lenscode.app.voice.VoiceAction.AcceptChanges
        com.lenscode.app.voice.VoiceCommand.DiscardChanges -> com.lenscode.app.voice.VoiceAction.DiscardChanges
        com.lenscode.app.voice.VoiceCommand.NewTerminal -> com.lenscode.app.voice.VoiceAction.NewTerminal
        com.lenscode.app.voice.VoiceCommand.KillTerminal -> com.lenscode.app.voice.VoiceAction.KillTerminal
        is com.lenscode.app.voice.VoiceCommand.CloseTerminal -> com.lenscode.app.voice.VoiceAction.CloseTerminal(target)
        is com.lenscode.app.voice.VoiceCommand.ArchiveAgent -> com.lenscode.app.voice.VoiceAction.ArchiveAgent(target)
        is com.lenscode.app.voice.VoiceCommand.OpenAgent -> com.lenscode.app.voice.VoiceAction.OpenAgent(name)
        com.lenscode.app.voice.VoiceCommand.Dismiss -> com.lenscode.app.voice.VoiceAction.Dismiss
        is com.lenscode.app.voice.VoiceCommand.Search -> com.lenscode.app.voice.VoiceAction.Search(query)
        is com.lenscode.app.voice.VoiceCommand.SetFontSize ->
            com.lenscode.app.voice.VoiceAction.SetFontSize(size, change)
    }

    val geminiConfigResult = remember(ui.geminiConfigReloadKey) {
        com.lenscode.app.net.GeminiConfigStore.load(context)
    }

    val geminiKeySource = com.lenscode.app.net.resolveGeminiKeySource(
        file = geminiConfigResult,
        buildKeyPresent = BuildConfig.GEMINI_API_KEY.isNotBlank(),
        bridge = bridgeGemini,
    )

    /**
     * Koog/Gemini router — null (and every call skipped) when no API key is
     * configured, so an un-keyed build/device degrades straight to the
     * deterministic local router rather than making a doomed network call.
     * The key can come from three places (see resolveGeminiKeySource):
     *   - on-device: lenscode-config.json pushed with adb
     *   - the bridge: pasted once at its /setup page from a phone or PC — the
     *     normal path, since typing a key in AR is miserable; calls then go
     *     through the bridge's /gemini proxy and the key never reaches the headset
     *   - build-time: ./gradlew :app:assembleDebug -PgeminiApiKey=AIza...
     */
    val koogVoiceRouter = remember(ui.geminiConfigReloadKey, geminiKeySource, bridgeGemini.model) {
        val fileConfig = (geminiConfigResult as? com.lenscode.app.net.GeminiConfigResult.Loaded)?.config
        val fileModel = fileConfig?.model?.takeIf { it.isNotBlank() }
        when (geminiKeySource) {
            com.lenscode.app.net.GeminiKeySource.NONE -> null
            com.lenscode.app.net.GeminiKeySource.BRIDGE -> com.lenscode.app.voice.KoogVoiceRouter(
                // Koog requires a key; the bridge discards this one and adds the real one.
                apiKey = "via-lenscode-bridge",
                modelId = fileModel ?: bridgeGemini.model ?: BuildConfig.GEMINI_MODEL,
                baseUrl = bridge.geminiProxyBase,
                extraHeaders = bridge::geminiAuthHeaders,
            )
            com.lenscode.app.net.GeminiKeySource.CONFIG_FILE -> com.lenscode.app.voice.KoogVoiceRouter(
                apiKey = checkNotNull(fileConfig?.apiKey),
                modelId = fileModel ?: BuildConfig.GEMINI_MODEL,
            )
            com.lenscode.app.net.GeminiKeySource.BUILD -> com.lenscode.app.voice.KoogVoiceRouter(
                apiKey = BuildConfig.GEMINI_API_KEY,
                modelId = fileModel ?: BuildConfig.GEMINI_MODEL,
            )
        }
    }
    val voiceScope = rememberCoroutineScope()

    /**
     * Submit handler for the voice-command review card. Tries the agentic
     * Koog/Gemini router first; on any failure (no key, network, malformed
     * response) falls back to the deterministic local router rather than
     * leaving the command unhandled — the fallback always succeeds (worst
     * case it resolves to a plain file search of the raw transcript).
     */
    fun submitVoiceCommand(transcript: String) {
        val router = koogVoiceRouter
        if (router == null) {
            android.util.Log.d("LensCodeVoice", "no Gemini key configured — using local router")
            executeVoiceAction(com.lenscode.app.voice.parseVoiceCommand(transcript).toAction())
            ui.voiceCommandReview = null
            return
        }
        val context = com.lenscode.app.voice.VoiceRouterContext(
            activeView = ui.activeView,
            diffFileOpen = gitDiffPath,
            terminalSelected = ui.selectedPane != null,
            agentCardOpen = ui.agentDetail?.agent?.label,
            hasPendingPermission = permissions.isNotEmpty(),
            hasConfirmation = ui.deleteConfirm != null || ui.diffDiscardConfirm != null,
        )
        // ui.voiceCommandReview stays set (not nulled here) so the overlay keeps
        // showing the transcript through the "Thinking…" state below.
        ui.isRoutingVoiceCommand = true
        voiceScope.launch {
            // Confirmed live: router.route()'s Result<VoiceCommandPlan> does
            // NOT actually guarantee every failure is caught — a raw network
            // exception (DNS resolution failure, no connectivity) escaped
            // Koog's own Result-wrapping and crashed the whole app via this
            // otherwise-unhandled coroutine. A wrapping try/catch is required
            // for the "always falls back, never crashes" guarantee this
            // function's own doc comment already promised but didn't deliver.
            val result = try {
                router.route(transcript, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // structured concurrency — never swallow, let it propagate
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.isRoutingVoiceCommand = false
            ui.voiceCommandReview = null
            result.onSuccess { plan ->
                android.util.Log.d("LensCodeVoice", "Gemini plan: $plan")
                executeVoicePlan(plan)
            }.onFailure { e ->
                android.util.Log.w("LensCodeVoice", "Gemini routing failed, falling back to local router", e)
                executeVoiceAction(com.lenscode.app.voice.parseVoiceCommand(transcript).toAction())
            }
        }
    }

    // A confident hit opens straight away; anything else surfaces candidates
    // (or "no match") through the overlay instead of ever guessing (spec §13).
    LaunchedEffect(voiceResult) {
        val result = voiceResult ?: return@LaunchedEffect
        if (result.confident && result.resolvedPath != null) {
            bridge.openFile(result.resolvedPath)
            ui.activeView = "code"
            bridge.clearVoiceResult()
        }
    }

    // Keeps bridge.agentTimeline live for whichever agent's detail card is
    // open, and stops (clearing it) the moment it closes — one place to get
    // this right instead of a trackAgent() call at every site that opens or
    // nulls ui.agentDetail (openAgentDetail, dismiss, archive, send-and-close…).
    LaunchedEffect(ui.agentDetail?.agent?.agentId) {
        bridge.trackAgent(ui.agentDetail?.agent?.agentId)
    }

    // Refresh the data a view needs the moment it becomes active, rather than
    // only at connect time — dock switches are the user's "load this" signal.
    // Code is skipped when we already have a listing: jumping here from a
    // worktree click already fired its own dir.list at the new root, and
    // re-requesting the (stale, pre-switch) dirPath here would race it.
    LaunchedEffect(ui.activeView) {
        when (ui.activeView) {
            "terminal" -> bridge.refreshTerminals()
            "code" -> {
                if (dirEntries.isEmpty()) bridge.listDir(dirPath)
                // The composer's branch menu and model menu read these.
                bridge.refreshBranches()
                bridge.refreshWorktrees()
                bridge.refreshMachines()
                if (providers.isEmpty()) bridge.refreshProviders()
            }
            "agents" -> bridge.refreshAgents()
            "permissions" -> {
                bridge.refreshPermissions()
                bridge.refreshAgents()
            }
            "diff" -> bridge.refreshGitStatus()
            "remote" -> bridge.refreshMachines()
        }
    }

    val primaryLayout = rememberPanelLayoutState(layoutStore, "primary", 900f, 520f, ui.layoutGeneration)
    // Tied to the Code view so navigating away (dock, voice) always brings the dock back.
    val codeFullscreen = ui.codeFullscreen && ui.activeView == "code"
    val panelOffset = primaryLayout.offset
    val panelSize = primaryLayout.size

    val xrSession = LocalSession.current
    val headTrackingPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ui.headTrackingPermissionAttempt++
        } else {
            android.util.Log.w("LensCodeFollowMe", "HEAD_TRACKING permission denied — Follow Me can't work")
        }
    }
    LaunchedEffect(xrSession, ui.headTrackingPermissionAttempt) {
        if (xrSession == null) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, "android.permission.HEAD_TRACKING") ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            headTrackingPermission.launch("android.permission.HEAD_TRACKING")
            return@LaunchedEffect
        }
        val result = xrSession.configure(
            androidx.xr.runtime.Config.Builder(xrSession.config)
                .setDeviceTracking(androidx.xr.runtime.DeviceTrackingMode.SPATIAL)
                .build(),
        )
        ui.deviceTrackingReady = result is androidx.xr.runtime.SessionConfigureSuccess
        if (!ui.deviceTrackingReady) {
            android.util.Log.w("LensCodeFollowMe", "device tracking configure failed: $result")
        }
    }

    // Shared by the spatial panels and the flat Home Space layout.
    @Composable
    fun primaryContent() {
                SnipRegion(snipController, "primary", "main", Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                when (ui.activeView) {
                    "terminal" -> TerminalPanelContent(
                        terminals = terminals,
                        machineId = terminalsMachineId,
                        selected = ui.selectedPane,
                        output = terminalOutput,
                        onSelect = { pane ->
                            ui.selectedPane = pane
                            bridge.readTerminal(pane.terminalId, pane.machineId)
                        },
                        onNew = { bridge.createTerminal() },
                        onClose = { pane ->
                            bridge.closeTerminal(pane.terminalId, pane.machineId) {
                                if (ui.selectedPane?.terminalId == pane.terminalId) ui.selectedPane = null
                            }
                        },
                        onRequestStop = { pane ->
                            // Paseo has no per-terminal "what's running" RPC
                            // (docs/paseo-migration-plan.md, Phase 3) — the card
                            // can only confirm the action, not name the process.
                            ui.stopConfirm = com.lenscode.app.ui.StopConfirmState(
                                pane = pane,
                                processLabel = "the running process",
                                onConfirm = {
                                    bridge.interruptTerminal(pane.terminalId, pane.machineId)
                                    ui.stopConfirm = null
                                },
                                onCancel = { ui.stopConfirm = null },
                            )
                        },
                        stopConfirm = ui.stopConfirm,
                    )
                    "code" -> FilesPanelContent(
                        rootLabel = filesRootLabel,
                        path = dirPath,
                        entries = dirEntries,
                        file = openFile,
                        onOpenDir = { bridge.listDir(it) },
                        onOpenFile = {
                            ui.fileSearchQuery = ""
                            bridge.clearSearchResults()
                            bridge.openFile(it)
                        },
                        onUp = {
                            val parent = dirPath.substringBeforeLast('/', ".")
                            bridge.listDir(parent)
                        },
                        searchQuery = ui.fileSearchQuery,
                        onSearchQueryChange = { q ->
                            ui.fileSearchQuery = q
                            bridge.searchFiles(q)
                        },
                        searchResults = fileSearchResults,
                        fullscreen = codeFullscreen,
                        onToggleFullscreen = { ui.codeFullscreen = !ui.codeFullscreen },
                        codeFontSize = ui.editorFontSize,
                        composer = {
                            com.lenscode.app.ui.AgentComposer(
                                state = composer,
                                machineLabel = if (filesMachineIdFlow == "local") "Select machine"
                                    else machines.firstOrNull { it.id == filesMachineIdFlow }?.label?.ifBlank { null } ?: filesMachineIdFlow,
                                machineId = filesMachineIdFlow,
                                // Connects, then the folder picker opens; the chip and
                                // folder update once a folder is chosen.
                                onSelectMachine = { m -> bridge.connectMachine(m.id, m.label.ifBlank { m.id }) },
                                folderLabel = filesRootLabel,
                                machines = machines,
                                branches = branches,
                                currentBranch = currentBranch,
                                worktrees = worktrees,
                                providers = providers,
                                isListening = ui.isListening && ui.voiceMode == "composer",
                                dictationHint =
                                    if (ui.isListening && ui.voiceMode == "composer") ui.voicePartial else null,
                                onMic = { requestVoice("composer") },
                                onSend = { sendComposer() },
                                onSwitchBranch = { switchBranchFlow(it) },
                                onOpenWorktree = { wt -> bridge.openWorktreeInCode(wt) { ui.activeView = "code" } },
                                onCreateWorktree = { branch -> createWorktreeFlow(branch) },
                                onRemoveWorktree = { wt -> confirmRemoveWorktree(wt) },
                                onModeChosen = { chooseMode(it) },
                                onModelChosen = { provider, model -> chooseModel(provider, model) },
                            )
                        },
                    )
                    "agents" -> AgentsPanelContent(
                        agents,
                        onSelect = { openAgentDetail(it) },
                        onArchive = { agent ->
                            bridge.archiveAgent(agent.agentId)
                            if (ui.voiceAgentId == agent.agentId) ui.voiceAgentId = null
                            if (ui.agentDetail?.agent?.agentId == agent.agentId) ui.agentDetail = null
                        },
                        history = agentHistory,
                        historyHasMore = agentHistoryHasMore,
                        onShowHistory = { bridge.refreshAgentHistory() },
                        onLoadMoreHistory = { bridge.loadMoreAgentHistory() },
                        onSelectHistory = { entry -> openAgentDetail(entry.toAgentInfo()) },
                    )
                    "permissions" -> PermissionsPanelContent(
                        permissions = permissions,
                        agents = agents,
                        onRespond = { permission, behavior, selectedActionId ->
                            bridge.respondToPermission(permission, behavior, selectedActionId)
                        },
                    )
                    "settings" -> com.lenscode.app.ui.SettingsPanelContent(
                        bridge = com.lenscode.app.ui.BridgeSettingsUi(
                            currentUrl = state.bridgeUrl,
                            connected = state.bridgeConnected,
                            connectError = state.connectError,
                            input = ui.bridgeAddressInput,
                            // A recognizer failure ("Didn't catch that") would otherwise be invisible here.
                            inputError = ui.bridgeAddressError
                                ?: ui.voiceError.takeIf { ui.voiceMode == "bridgeAddress" },
                            listening = ui.isListening && ui.voiceMode == "bridgeAddress",
                            onInputChange = {
                                ui.bridgeAddressInput = it
                                ui.bridgeAddressError = null
                            },
                            onSpeak = { requestVoice("bridgeAddress") },
                            onSave = { saveBridgeAddress() },
                        ),
                        geminiResult = geminiConfigResult,
                        geminiKeySource = geminiKeySource,
                        bridgeGemini = bridgeGemini,
                        bridgeConnected = state.bridgeConnected,
                        setupUrl = bridge.geminiSetupUrl,
                        configPath = com.lenscode.app.net.GeminiConfigStore.configFile(context).absolutePath,
                        onReload = { ui.geminiConfigReloadKey++ },
                        currentThemeId = ui.currentThemeId,
                        onThemeChange = { id ->
                            ui.currentThemeId = id
                            themeStore.save(id)
                            com.lenscode.app.ui.applyAppTheme(id)
                        },
                    )
                    "diff" -> DiffPanelContent(
                        status = gitStatus,
                        diffPath = gitDiffPath,
                        diffText = gitDiffText,
                        onSelectFile = { path ->
                            ui.stagedHunks = emptySet()
                            bridge.loadGitDiff(path)
                        },
                        onStageHunk = { path, patch ->
                            bridge.applyHunk(path, patch, "stage", onDone = {
                                // The Diff view always shows working-tree-vs-HEAD
                                // regardless of index state (see git.ts), so a
                                // successful stage genuinely doesn't change what's
                                // rendered — only this local badge reflects it.
                                ui.stagedHunks = ui.stagedHunks + patch.hashCode()
                            }, onError = {})
                        },
                        onDiscardHunk = { path, patch ->
                            ui.diffDiscardConfirm = com.lenscode.app.ui.DeleteConfirmState(
                                title = "Discard this hunk?",
                                message = "This reverts those lines in $path back to HEAD. Uncommitted changes in this hunk are lost.",
                                onConfirm = {
                                    bridge.applyHunk(path, patch, "discard", onDone = {
                                        ui.diffDiscardConfirm = null
                                        bridge.loadGitDiff(path) // the hunk should be gone now
                                    }, onError = { ui.diffDiscardConfirm = null })
                                },
                                onCancel = { ui.diffDiscardConfirm = null },
                            )
                        },
                        discardConfirm = ui.diffDiscardConfirm,
                        stagedHunks = ui.stagedHunks,
                    )
                    "browser" -> com.lenscode.app.ui.BrowserPanelContent(
                        url = ui.browserUrl,
                        onChangeUrl = { ui.browserOverlayOpen = true },
                        onOpenInChrome = { openInChrome(ui.browserUrl) },
                    )
                    "remote" -> RemotePanelContent(
                        machines = machines,
                        connectingMachineId = connectingMachineId,
                        connectError = machineConnectError,
                        onConnect = { machineId ->
                            val label = machines.find { it.id == machineId }?.label ?: machineId
                            bridge.connectMachine(machineId, label)
                        },
                        onDisconnect = { machineId ->
                            bridge.disconnectMachine(machineId)
                            // If the Terminal view was showing this machine, fall back
                            // to local rather than silently pointing at a dead tunnel.
                            if (bridge.terminalsMachineId.value == machineId) {
                                bridge.switchTerminalsMachine("local")
                            }
                        },
                        onViewTerminals = { machineId ->
                            bridge.switchTerminalsMachine(machineId)
                            ui.activeView = "terminal"
                        },
                        onBrowse = { machineId ->
                            val label = machines.find { it.id == machineId }?.label ?: machineId
                            bridge.startRemoteBrowse(machineId, label, "/")
                        },
                    )
                    else -> PrimaryPanelContent(
                        state,
                        onResetLayout = { resetLayout() },
                        pinned = primaryLayout.pinned,
                        onTogglePin = { primaryLayout.togglePinned() },
                        onBringCloser = { primaryLayout.nudgeDepth(-40f) },
                        onPushFarther = { primaryLayout.nudgeDepth(40f) },
                        followMeEnabled = ui.followMeEnabled,
                        onToggleFollowMe = { ui.followMeEnabled = !ui.followMeEnabled },
                        onEnterPip = onEnterPip,
                        // Pin / depth / Follow Me / PiP only mean something for spatial panels.
                        showSpatialControls = androidx.xr.compose.platform.LocalSpatialCapabilities.current.isSpatialUiEnabled,
                        pairingState = pairingState,
                        deviceName = ui.pairingDeviceName,
                        onDeviceNameChange = { ui.pairingDeviceName = it },
                        onBeginPairing = { bridge.beginPairing(ui.pairingDeviceName) },
                        pairingCode = ui.pairingCode,
                        onPairingCodeChange = { ui.pairingCode = it },
                        onSubmitPairingCode = {
                            bridge.completePairing(ui.pairingDeviceName, ui.pairingCode)
                        },
                        onRetryPairing = {
                            ui.pairingCode = ""
                            bridge.beginPairing(ui.pairingDeviceName)
                        },
                        weather = ui.weatherReading,
                        weatherLocationDenied = ui.weatherLocationDenied,
                        projectLabel = filesRootLabel,
                        projectMachineId = filesMachineIdFlow,
                        projectRootPath = projectRootPath,
                    )
                }
                // Voice search is reachable from any view (dock mic button
                // below), so its overlay lives here rather than inside one
                // specific view's content.
                VoiceOverlay(
                    isListening = ui.isListening && ui.voiceMode == "fileSearch",
                    partial = ui.voicePartial,
                    error = if (ui.voiceMode == "fileSearch") ui.voiceError else null,
                    result = voiceResult,
                    onSelectCandidate = { path ->
                        bridge.openFile(path)
                        ui.activeView = "code"
                        bridge.clearVoiceResult()
                    },
                    onDismiss = {
                        ui.isListening = false
                        ui.voiceError = null
                        bridge.clearVoiceResult()
                    },
                )
                // Global voice command mic (dock 🎤 / walking mode) — record,
                // review the transcript, then Submit routes it through
                // dispatchVoiceCommand. An unrecognized command falls back to
                // voiceSearch(), which is what populates the VoiceOverlay
                // above with candidates, same as before this router existed.
                com.lenscode.app.ui.VoiceCommandOverlay(
                    isListening = ui.isListening && ui.voiceMode == "command",
                    partial = ui.voicePartial,
                    error = if (ui.voiceMode == "command") ui.voiceError else null,
                    reviewText = ui.voiceCommandReview,
                    isRouting = ui.isRoutingVoiceCommand,
                    onSubmit = {
                        // Don't null ui.voiceCommandReview here — submitVoiceCommand
                        // keeps it set through routing so the "Thinking…" state
                        // (ui.isRoutingVoiceCommand) has a transcript to show.
                        val text = ui.voiceCommandReview
                        if (!text.isNullOrBlank()) submitVoiceCommand(text) else ui.voiceCommandReview = null
                    },
                    onCancel = { ui.voiceCommandReview = null },
                    onDismissError = {
                        ui.isListening = false
                        ui.voiceError = null
                    },
                )
                // Agent chips (strip above, or the Agents view) expand here
                // rather than navigating away — see openAgentDetail's doc.
                com.lenscode.app.ui.AgentDetailOverlay(
                    state = ui.agentDetail,
                    timeline = agentTimeline,
                    promptText = ui.agentPromptText,
                    onPromptChange = { ui.agentPromptText = it },
                    isListeningForPrompt = ui.isListening && ui.voiceMode == "agentPrompt",
                    onVoiceInput = { requestVoice("agentPrompt") },
                    onSendPrompt = {
                        val target = ui.agentDetail?.agent
                        if (target != null && ui.agentPromptText.isNotBlank()) {
                            bridge.sendAgentMessage(
                                target.agentId,
                                ui.agentPromptText,
                                onDone = {
                                    ui.agentPromptText = ""
                                    ui.agentDetail = null
                                },
                                onError = {
                                    ui.agentDetail = ui.agentDetail?.copy(
                                        promptError = "Couldn't send — try again in a moment.",
                                    )
                                },
                            )
                        }
                    },
                    onArchive = {
                        val target = ui.agentDetail?.agent
                        if (target != null) {
                            ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
                                title = "Archive this agent?",
                                message = "This closes ${target.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                                onConfirm = {
                                    bridge.archiveAgent(target.agentId)
                                    ui.deleteConfirm = null
                                    ui.agentDetail = null
                                },
                                onCancel = { ui.deleteConfirm = null },
                            )
                        }
                    },
                    onOpenWorkspace = {
                        ui.agentDetail?.agent?.let { openAgentWorkspace(it) }
                    },
                    onDismiss = { ui.agentDetail = null },
                )
                // Confirmation cards live at the overlay level now that the Git
                // tab (their old home) is gone — branch-switch conflicts, agent
                // archive, Bypass enable. Voice answers them with "confirm"/"cancel".
                com.lenscode.app.ui.BranchConflictCard(ui.branchConflict)
                com.lenscode.app.ui.DeleteConfirmCard(ui.deleteConfirm)
                // A bridge using its own certificate (--secure): confirm its fingerprint once.
                com.lenscode.app.ui.CertTrustOverlay(
                    prompt = certPrompt,
                    onTrust = { bridge.trustCertificate() },
                    onDecline = { bridge.declineCertificate() },
                )
                // Shown right after a successful Connect (spec §37) — picking a
                // folder here re-roots both Code and a fresh Terminal at it.
                RemoteBrowseOverlay(
                    state = remoteBrowse,
                    onNavigate = { path -> bridge.browseRemoteDir(path) },
                    onUp = { bridge.browseRemoteUp() },
                    onOpen = {
                        bridge.openRemoteBrowsedFolder()
                        ui.activeView = "code"
                    },
                    onDismiss = { bridge.closeRemoteBrowse() },
                )
                com.lenscode.app.ui.BrowserLaunchOverlay(
                    visible = ui.browserOverlayOpen,
                    url = ui.browserUrl,
                    onUrlChange = { ui.browserUrl = it },
                    onOpen = { openInAppBrowser(ui.browserUrl) },
                    onDismiss = { ui.browserOverlayOpen = false },
                    isListeningForUrl = ui.isListening && ui.voiceMode == "browserUrl",
                    onVoiceInput = { requestVoice("browserUrl") },
                )
                ui.snipBitmap?.let { bitmap ->
                    SnipCropOverlay(
                        bitmap = bitmap,
                        uploading = ui.snipUploading,
                        onConfirm = { rect -> confirmSnip(rect) },
                        onSave = { rect -> saveSnipToGallery(rect) },
                        onCancel = { ui.snipBitmap = null },
                    )
                }
                ui.snipPendingMessage?.let { message ->
                    com.lenscode.app.ui.AgentPickerOverlay(
                        title = "Attach screenshot to which agent?",
                        agents = ui.snipPickerAgents,
                        onSelect = { agent ->
                            attachSnipToAgent(agent, message)
                            ui.snipPendingMessage = null
                            ui.snipPickerAgents = emptyList()
                        },
                        onDismiss = {
                            ui.snipPendingMessage = null
                            ui.snipPickerAgents = emptyList()
                            showToast("Screenshot discarded — no agent selected.")
                        },
                    )
                }
                if (ui.archivePickerAgents.isNotEmpty()) {
                    com.lenscode.app.ui.AgentPickerOverlay(
                        title = "Archive which agent?",
                        agents = ui.archivePickerAgents,
                        onSelect = { agent ->
                            ui.archivePickerAgents = emptyList()
                            ui.deleteConfirm = com.lenscode.app.ui.DeleteConfirmState(
                                title = "Archive this agent?",
                                message = "This closes ${agent.label}'s runtime. Its history stays in Paseo, but it can't take new messages.",
                                onConfirm = {
                                    bridge.archiveAgent(agent.agentId)
                                    if (ui.voiceAgentId == agent.agentId) ui.voiceAgentId = null
                                    ui.deleteConfirm = null
                                    if (ui.agentDetail?.agent?.agentId == agent.agentId) ui.agentDetail = null
                                },
                                onCancel = { ui.deleteConfirm = null },
                            )
                        },
                        onDismiss = {
                            ui.archivePickerAgents = emptyList()
                            showToast("Archive canceled — no agent selected.")
                        },
                    )
                }
                if (ui.openAgentPickerAgents.isNotEmpty()) {
                    com.lenscode.app.ui.AgentPickerOverlay(
                        title = "Open which agent?",
                        agents = ui.openAgentPickerAgents,
                        onSelect = { agent ->
                            ui.openAgentPickerAgents = emptyList()
                            openAgentDetail(agent)
                            ui.activeView = "agents"
                        },
                        onDismiss = {
                            ui.openAgentPickerAgents = emptyList()
                            showToast("Canceled — no agent selected.")
                        },
                    )
                }
                }
                }
    }

    @Composable
    fun dockContent() {
                    SnipRegion(snipController, "dock", "main", Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in listOf(
                            // Only way back to the primary panel once you've
                            // navigated elsewhere — that's where Follow Me,
                            // Walking Mode, Pin, and pairing status all live,
                            // and there was previously no dock entry back to it.
                            "home" to "Home",
                            "code" to "Code",
                            "terminal" to "Terminal",
                            "diff" to "Diff",
                            "agents" to "Agents",
                            "permissions" to "Permissions",
                            "remote" to "Remote",
                            "voice" to "Voice",
                            "snip" to "Snip",
                            "browser" to "Browser",
                            "settings" to "Settings",
                        )) {
                            androidx.compose.runtime.key(id) {
                                Box(Modifier.weight(1f)) {
                                    DockButtonContent(
                                        id,
                                        label,
                                        active = ui.activeView == id,
                                        attention = id == "permissions" && permissions.isNotEmpty(),
                                    ) {
                                        when (id) {
                                            "voice" -> requestVoiceCommand()
                                            "snip" -> startSnip()
                                            "browser" -> ui.browserOverlayOpen = true
                                            else -> ui.activeView = id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
    }

    // Home Space has no Subspace, so lay the same content out as one flat 2D
    // screen: agent strip, primary panel, dock.
    @Composable
    fun flatContent() {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(LensCodeColors.WindowBgAlt, LensCodeColors.WindowBg),
                    ),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (agents.isEmpty()) {
                        Box(Modifier.width(300.dp).fillMaxHeight().agentChipSurface(first = true)) {
                            AgentChipContent("—", "No agents running", LensCodeColors.Idle)
                        }
                    } else {
                        for (agent in agents) {
                            androidx.compose.runtime.key(agent.agentId) {
                                Box(
                                    Modifier.width(220.dp).fillMaxHeight()
                                        .agentChipSurface(first = agent === agents.first())
                                        .clickable { openAgentDetail(agent) },
                                ) {
                                    AgentChipContent(agent.label, agent.status, LensCodeColors.agentStatusColor(agent.status), agent.provider)
                                }
                            }
                        }
                    }
                }
                if (onEnterFullSpace != null) {
                    androidx.compose.material3.Button(onClick = onEnterFullSpace) {
                        androidx.compose.material3.Text("Full Space")
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(lensShape(LensCodeCorners.Panel))) {
                primaryContent()
            }
            if (!codeFullscreen) {
                Box(Modifier.fillMaxWidth().height(76.dp).clip(lensShape(LensCodeCorners.Control))) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lenscode.app.ui.LocalWindowCornerFollow provides true,
                    ) { dockContent() }
                }
            }
        }
    }

    // Factored out so the spatial root below stays a one-liner.
    @Composable
    fun androidx.xr.compose.subspace.SpatialBoxScope.spatialContent() {
            // ── Agent strip — real Paseo agent.list, honestly empty if none ──
            // All three rows share the main panel's horizontal/depth anchor.
            // A 12 dp gap separates each accessory row from its visible edge.
            SpatialRow(SubspaceModifier.offset(
                x = panelOffset.x.dp,
                y = (panelOffset.y + panelSize.height / 2f + 12f + 55f).dp,
                z = panelOffset.z.dp,
            )) {
                if (agents.isEmpty()) {
                    SpatialPanel(
                        SubspaceModifier.width(460.dp).height(110.dp).padding(horizontal = 8.dp),
                        shape = SpatialRoundedCornerShape(CornerSize(LensCodeCorners.Card)),
                    ) {
                        SnipRegion(snipController, "agents", "empty") {
                            AgentChipContent("—", "No agents running", LensCodeColors.Idle)
                        }
                    }
                } else {
                    for (agent in agents) {
                        androidx.compose.runtime.key(agent.agentId) {
                            SpatialPanel(
                                SubspaceModifier.width(220.dp).height(110.dp).padding(horizontal = 8.dp),
                                shape = SpatialRoundedCornerShape(CornerSize(LensCodeCorners.Card)),
                            ) {
                                SnipRegion(snipController, "agents", agent.agentId, Modifier.fillMaxSize().clickable { openAgentDetail(agent) }) {
                                    AgentChipContent(agent.label, agent.status, LensCodeColors.agentStatusColor(agent.status), agent.provider)
                                }
                            }
                        }
                    }
                }
            }

            // ── Primary panel ──────────────────────────────────────────────
            SpatialPanel(primaryLayout.toModifier(0.dp), shape = SpatialRoundedCornerShape(CornerSize(LensCodeCorners.Panel))) {
                primaryContent()
            }

            // ── Quick dock ────────────────────────────────────────────────
            if (!codeFullscreen) run {
                // Follow the main panel's bottom edge, including live moves
                // and resizes, rather than anchoring to a separate world pose.
                SpatialPanel(
                    SubspaceModifier.width(panelSize.width.dp).height(76.dp)
                        .offset(
                            x = panelOffset.x.dp,
                            y = (panelOffset.y - panelSize.height / 2f - 12f - 38f).dp,
                            z = panelOffset.z.dp,
                        ),
                    shape = SpatialRoundedCornerShape(CornerSize(LensCodeCorners.Control)),
                ) {
                    dockContent()
                }
            }
    }

    // ── Follow Me ───────────────────────────────────────────────────────
    // The platform's own FollowingSubspace, configured the way its source
    // requires: FollowTarget.ArDevice is NOT compatible with
    // FollowBehavior.Tight (that's why Follow Me never showed anything — the
    // original code used Tight). Soft is the system-tuned lazy follow: content
    // glides back in front of you instead of being glued to every head twitch.
    // Tracks position + yaw only, so nodding/tilting doesn't swing the panels.
    // ArDevice(session) has no offset in this version and the subspace origin
    // sits at your head, so the layout is pushed out in front explicitly
    // (~0.6 m — tuned on-device to match how close the non-following layout feels).
    //
    // References:
    //   FollowingSubspace KDoc ("ArDevice is not compatible with Tight", Soft default):
    //     https://github.com/androidx/androidx/blob/androidx-main/xr/compose/compose/src/main/kotlin/androidx/xr/compose/spatial/Subspace.kt
    //   FollowBehavior (Soft / Static / Tight):
    //     https://developer.android.com/reference/kotlin/androidx/xr/compose/subspace/FollowBehavior
    //   XR Compose release notes (FollowingSubspace experimental in alpha17, UserSubspace soft locking):
    //     https://developer.android.com/jetpack/androidx/releases/xr-compose
    //   Device pose / HEAD_TRACKING + DeviceTrackingMode setup:
    //     https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/device-pose
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val dpPerMeter = remember(xrSession) {
        xrSession?.let { it.scene.virtualPixelDensity.pixelsPerMeter / density } ?: 1000f
    }
    LaunchedEffect(ui.followMeEnabled, ui.deviceTrackingReady) {
        android.util.Log.d("LensCodeFollowMe", "followMe=$ui.followMeEnabled trackingReady=$ui.deviceTrackingReady distance=${ui.followMeDistanceM}m")
    }
    if (!androidx.xr.compose.platform.LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        // The flat layout's agent chips sit outside any SnipRegion, so give it the font here too.
        com.lenscode.app.ui.ProvideLensFont { flatContent() }
    } else if (ui.followMeEnabled && xrSession != null && ui.deviceTrackingReady) {
        FollowingSubspace(
            FollowTarget.ArDevice(xrSession),
            FollowBehavior.Soft(),
            SubspaceModifier,
            // translation x/y/z, rotation x (pitch) / y (yaw) / z (roll)
            androidx.xr.compose.subspace.TrackedDimensions(true, true, true, false, true, false),
        ) {
            SpatialBox(SubspaceModifier.offset(0.dp, 0.dp, (-ui.followMeDistanceM * dpPerMeter).dp)) {
                spatialContent()
            }
        }
    } else {
        Subspace {
            SpatialBox {
                spatialContent()
            }
        }
    }
}
