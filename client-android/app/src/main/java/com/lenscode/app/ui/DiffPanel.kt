package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * State for the branch-switch dirty-conflict card (spec §27 — never resolve
 * uncommitted changes silently). Shown whenever the bridge reports the
 * working tree is dirty; the user picks how to handle it.
 */
data class BranchConflictState(
    val target: String,
    val onStash: () -> Unit,
    val onWorktree: () -> Unit,
    val onCancel: () -> Unit,
)

/** Generic destructive-action confirmation (spec §57) — branch delete, worktree removal, etc. */
data class DeleteConfirmState(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val confirmLabel: String = "Delete",
)

/**
 * Dirty-tree branch-switch card (spec §27). Rendered at the app's overlay level
 * now that the Git tab is gone — branch switching lives in the Code composer.
 */
@Composable
fun BranchConflictCard(conflict: BranchConflictState?) {
    if (conflict != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        "Uncommitted changes",
                        color = LensCodeColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Switching to ${conflict.target} would affect files you haven't committed.",
                        color = LensCodeColors.Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Column {
                        Text(
                            "Stash changes and switch",
                            color = LensCodeColors.Amber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onStash).padding(vertical = 8.dp),
                        )
                        Text(
                            "Open in a new worktree instead",
                            color = LensCodeColors.Cyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onWorktree).padding(vertical = 8.dp),
                        )
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = conflict.onCancel).padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Destructive-action confirmation card (spec §57), shown app-wide over whatever view asked for it. */
@Composable
fun DeleteConfirmCard(deleteConfirm: DeleteConfirmState?) {
    if (deleteConfirm != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(deleteConfirm.title, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(deleteConfirm.message, color = LensCodeColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = deleteConfirm.onCancel).padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            deleteConfirm.confirmLabel,
                            color = LensCodeColors.Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = deleteConfirm.onConfirm).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Badge color per `GitFileStatus.status` (added/modified/deleted/renamed/untracked). */
private fun gitStatusColor(status: String): Color = when (status) {
    "added" -> LensCodeColors.Green
    "deleted" -> LensCodeColors.Red
    "renamed" -> LensCodeColors.Purple
    "untracked" -> LensCodeColors.Cyan
    else -> LensCodeColors.Amber // modified
}

private fun gitStatusLabel(status: String): String = when (status) {
    "added" -> "A"
    "deleted" -> "D"
    "renamed" -> "R"
    "untracked" -> "U"
    else -> "M"
}

/** One `@@ … @@` block (its header line + every line until the next hunk/EOF). */
data class DiffHunk(val lines: List<String>)

/**
 * Splits a unified diff for one file into its file-header lines (`diff --git`,
 * `index`, `---`, `+++`) and its hunks — used both for display and to
 * reconstruct a single-hunk patch for `git.hunk.apply` (spec §31): a valid
 * patch is just the file header followed by exactly one `@@` block.
 */
fun parseDiffHunks(diffText: String): Pair<List<String>, List<DiffHunk>> {
    val lines = diffText.split("\n")
    val firstHunkIdx = lines.indexOfFirst { it.startsWith("@@") }
    if (firstHunkIdx == -1) return lines to emptyList()
    val header = lines.subList(0, firstHunkIdx)
    val hunks = mutableListOf<DiffHunk>()
    var start = firstHunkIdx
    for (i in (firstHunkIdx + 1)..lines.size) {
        if (i == lines.size || lines[i].startsWith("@@")) {
            hunks.add(DiffHunk(lines.subList(start, i)))
            start = i
        }
    }
    return header to hunks
}

/** One hunk's patch text, ready to pipe to `git apply` (spec §31). */
fun hunkPatch(header: List<String>, hunk: DiffHunk): String =
    (header + hunk.lines).joinToString("\n") + "\n"

/**
 * Diff view: real `git status` on the left (scoped to whatever the Code view
 * is currently rooted at), a colored unified diff for the selected file on
 * the right — VS Code-style +/- line tinting, no external diff library.
 * Each hunk carries its own Stage/Discard row (spec §31) — "stage" adds just
 * that hunk to the index via `git apply --cached`; "discard" is destructive
 * (spec §57) and reverses it out of the working tree, gated on the same
 * confirmation-card pattern used for branch/worktree deletion.
 */
@Composable
fun DiffPanelContent(
    status: com.lenscode.app.net.GitStatus?,
    diffPath: String?,
    diffText: String?,
    onSelectFile: (String) -> Unit,
    onStageHunk: (path: String, patch: String) -> Unit = { _, _ -> },
    onDiscardHunk: (path: String, patch: String) -> Unit = { _, _ -> },
    discardConfirm: DeleteConfirmState? = null,
    stagedHunks: Set<Int> = emptySet(),
) {
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.width(240.dp).fillMaxSize()) {
                Eyebrow("DIFF" + (status?.branch?.let { " · $it" } ?: ""))
                Spacer(Modifier.height(10.dp))
                val files = status?.files.orEmpty()
                if (status == null) {
                    Text("Loading…", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else if (files.isEmpty()) {
                    Text("No changes.", color = LensCodeColors.Muted, fontSize = 13.sp)
                } else {
                    LazyColumn {
                        items(files) { f ->
                            val isSelected = f.path == diffPath
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(lensShape(3.dp))
                                    .background(if (isSelected) LensCodeColors.DockActiveGradTop else Color.Transparent)
                                    .clickable { onSelectFile(f.path) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    gitStatusLabel(f.status),
                                    color = gitStatusColor(f.status),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(18.dp),
                                )
                                Text(
                                    f.path.substringAfterLast('/'),
                                    color = LensCodeColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(lensShape(3.dp))
                    .background(VsCodeColors.EditorBg)
                    .padding(14.dp),
            ) {
                when {
                    diffPath == null -> Text("Select a changed file.", color = LensCodeColors.Muted, fontSize = 13.sp)
                    diffText == null -> Text("Loading diff…", color = LensCodeColors.Muted, fontSize = 13.sp)
                    diffText.isBlank() -> Text("No textual diff to show.", color = LensCodeColors.Muted, fontSize = 13.sp)
                    else -> {
                        val (header, hunks) = remember(diffText) { parseDiffHunks(diffText) }
                        fun lineStyle(line: String): Pair<Color, Color> = when {
                            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ->
                                VsCodeColors.Breadcrumb to Color.Transparent
                            line.startsWith("@@") -> LensCodeColors.Purple to Color.Transparent
                            line.startsWith("+") -> Color(0xFFB8E6B0) to Color(0x2A3FB950)
                            line.startsWith("-") -> Color(0xFFF2B8B5) to Color(0x2AF85149)
                            else -> VsCodeColors.Plain to Color.Transparent
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(header) { line ->
                                val (color, bg) = lineStyle(line)
                                Text(
                                    line,
                                    color = color,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().background(bg),
                                )
                            }
                            itemsIndexed(hunks) { index, hunk ->
                                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    for (line in hunk.lines) {
                                        val (color, bg) = lineStyle(line)
                                        Text(
                                            line,
                                            color = color,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.fillMaxWidth().background(bg),
                                        )
                                    }
                                    val staged = hunkPatch(header, hunk).hashCode() in stagedHunks
                                    Row(
                                        Modifier.padding(top = 2.dp, start = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (staged) {
                                            Text("✓ staged", color = LensCodeColors.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(
                                                "✓ Stage",
                                                color = LensCodeColors.Blue,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    onStageHunk(diffPath, hunkPatch(header, hunk))
                                                },
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            "✕ Discard",
                                            color = LensCodeColors.Red,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                onDiscardHunk(diffPath, hunkPatch(header, hunk))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Discard reverses a hunk out of the working tree — destructive (spec §57).
    if (discardConfirm != null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .glassFinish(PanelCorner)
                    .padding(20.dp),
            ) {
                Column {
                    Text(discardConfirm.title, color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(discardConfirm.message, color = LensCodeColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel",
                            color = LensCodeColors.Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = discardConfirm.onCancel).padding(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Discard",
                            color = LensCodeColors.Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = discardConfirm.onConfirm).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
    }
}
