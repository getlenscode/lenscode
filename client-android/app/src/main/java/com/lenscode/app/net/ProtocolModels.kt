package com.lenscode.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire-format models for the XR protocol (../../protocol) — every @Serializable
 * type here mirrors a payload the bridge sends or returns. Kept separate from
 * BridgeClient so the transport class isn't also the schema.
 *
 * Every field has a default matching what the old hand-rolled org.json parsing
 * produced (absent or null → the same value `optString`/`optBoolean` fell back
 * to), decoded with [wireJson]'s coerceInputValues so a null never crashes a
 * non-null field. Unknown fields are ignored — the bridge can add fields
 * without breaking older clients.
 */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Decode a whole response `result` object into [T]. */
internal inline fun <reified T> decodeResult(result: org.json.JSONObject): T =
    wireJson.decodeFromString(result.toString())

/** Decode the array named [key] in a response `result`, or emptyList if absent. */
internal inline fun <reified T> decodeList(result: org.json.JSONObject, key: String): List<T> {
    val arr = result.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { wireJson.decodeFromString(arr.getJSONObject(it).toString()) }
}

@Serializable
data class PaneInfo(
    val terminalId: String = "",
    val workspaceId: String? = null,
    val cwd: String = "",
    /** Wire sends this as "name"; callers fall back to [terminalId] when blank. */
    @SerialName("name") val title: String = "",
    /** Paseo's terminal activity state (idle/working/attention), or null if unknown.
     * Paseo has no server-side terminal focus (docs/paseo-migration-plan.md,
     * Phase 1), so this is what the status dot actually reflects. */
    val agentStatus: String? = null,
    /** "local" or a connected remote machine id (spec §37) — carried per-terminal so
     * terminal-scoped actions (read/close/interrupt) always target the right
     * machine, regardless of which machine's terminal list is on screen. */
    val machineId: String = "local",
)

@Serializable
data class DirEntry(
    val name: String = "",
    /** Wire "kind" is "dir" | "file" — [isDir] derives from it. */
    val kind: String = "file",
    val language: String? = null,
) {
    val isDir: Boolean get() = kind == "dir"
}

@Serializable
data class FileContent(
    val path: String = "",
    val content: String = "",
    val language: String? = null,
    val truncated: Boolean = false,
)

/**
 * A first-class Paseo agent (docs/paseo-migration-plan.md, Phase 2/3) — not a
 * terminal pane. Paseo tracks agents as their own objects.
 */
@Serializable
data class AgentInfo(
    val agentId: String = "",
    val provider: String = "agent",
    val model: String? = null,
    val title: String? = null,
    val status: String = "idle",
    val cwd: String = "",
    val workspaceId: String? = null,
    val requiresAttention: Boolean = false,
    val attentionReason: String? = null,
    val pendingPermissionCount: Int = 0,
    val lastError: String? = null,
    val costUsd: Double? = null,
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
@Serializable
data class AgentHistoryEntry(
    val agentId: String = "",
    val provider: String = "agent",
    val model: String? = null,
    val title: String? = null,
    val status: String = "closed",
    val cwd: String = "",
    val createdAt: String = "",
    val archivedAt: String? = null,
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
@Serializable
data class AgentTimelineEntry(
    val kind: String = "other",
    val text: String? = null,
    val toolName: String? = null,
    val command: String? = null,
    val output: String? = null,
    val status: String? = null,
    val timestamp: String = "",
)

/** One pending "approve this tool call" request (docs/paseo-migration-plan.md, Phase 3). */
@Serializable
data class PermissionAction(
    val id: String = "",
    val label: String = "",
    val behavior: String = "",
)

@Serializable
data class PermissionInfo(
    val permissionRequestId: String = "",
    val agentId: String = "",
    val kind: String = "other",
    val title: String? = null,
    val description: String? = null,
    val actions: List<PermissionAction> = emptyList(),
)

@Serializable
data class WorktreeInfo(
    val path: String = "",
    val branch: String? = null,
    val openWorkspaceId: String? = null,
)

@Serializable
data class GitFileStatus(
    val path: String = "",
    val oldPath: String? = null,
    val status: String = "",
)

@Serializable
data class VoiceCandidate(
    val path: String = "",
    val score: Double = 0.0,
    val preview: String? = null,
)

/** A typed `search.files` hit (spec §13 back-end) — filename or ripgrep text match. */
@Serializable
data class FileSearchHit(
    val path: String = "",
    val score: Double = 0.0,
    val kind: String = "filename",
    val preview: String? = null,
    val line: Int? = null,
)

@Serializable
data class VoiceResolution(
    val confident: Boolean = false,
    val resolvedPath: String? = null,
    val candidates: List<VoiceCandidate> = emptyList(),
)

@Serializable
data class GitStatus(
    val branch: String? = null,
    val files: List<GitFileStatus> = emptyList(),
)

/** One coding-agent provider from `provider.list`, with the model ids it offers. */
@Serializable
data class ProviderModeInfo(
    val id: String = "",
    val label: String = "",
    val description: String = "",
    val dangerous: Boolean = false,
)

@Serializable
data class ProviderInfo(
    val provider: String = "",
    val available: Boolean = false,
    val models: List<String> = emptyList(),
    val modes: List<ProviderModeInfo> = emptyList(),
)

@Serializable
data class MachineInfo(
    val id: String = "",
    val label: String = "",
    val connected: Boolean = false,
)

/** One row in the remote folder browser — same dir.list wire shape as [DirEntry]. */
@Serializable
data class RemoteBrowseEntry(
    val name: String = "",
    val kind: String = "file",
) {
    val isDir: Boolean get() = kind == "dir"
}

/** Client-side state for the remote folder browser overlay. */
data class RemoteBrowseState(
    val machineId: String,
    val machineLabel: String,
    val path: String,
    val entries: List<RemoteBrowseEntry>,
    val loading: Boolean = false,
)

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
