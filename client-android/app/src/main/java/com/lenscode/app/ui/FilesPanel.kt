package com.lenscode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenscode.app.net.DirEntry
import com.lenscode.app.net.FileContent
import kotlinx.coroutines.launch

/**
 * Code view styled like VS Code: an Explorer sidebar, a tab bar for the open
 * file, a breadcrumb showing its full path, and a gutter + syntax-highlighted
 * editor pane. Backed by real dir.list / file.open results from lenscode-bridge.
 */
@Composable
fun FilesPanelContent(
    rootLabel: String,
    path: String,
    entries: List<DirEntry>,
    file: FileContent?,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchResults: List<com.lenscode.app.net.FileSearchHit> = emptyList(),
    composer: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.fillMaxSize().glassFinish(PanelCorner),
    ) {
        Row(Modifier.fillMaxSize()) {
            // ── Explorer sidebar ─────────────────────────────────────────
            Column(
                Modifier
                    .width(220.dp)
                    .fillMaxSize()
                    .background(VsCodeColors.SidebarBg)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    "EXPLORER",
                    color = VsCodeColors.Breadcrumb,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Text(
                    rootLabel,
                    color = VsCodeColors.LineNumberActive,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                // Typed file search (spec §13 back-end) — separate from voice
                // search: a plain results list, no confidence gating, never
                // auto-opens anything.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(VsCodeColors.EditorBg)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (searchQuery.isEmpty()) {
                        Text("🔍 Search files…", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        textStyle = TextStyle(color = VsCodeColors.Plain, fontSize = 12.sp),
                        singleLine = true,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(VsCodeColors.Plain),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (searchQuery.isNotEmpty()) {
                    if (searchResults.isEmpty()) {
                        Text(
                            "No matches.",
                            color = VsCodeColors.Breadcrumb,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        LazyColumn {
                            items(searchResults) { hit ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFile(hit.path) }
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (hit.kind == "text") "🔎" else "📄", fontSize = 12.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(hit.path, color = VsCodeColors.Plain, fontSize = 13.sp)
                                    }
                                    if (hit.preview != null) {
                                        Text(
                                            hit.preview.trim().take(60) + (hit.line?.let { " · L$it" } ?: ""),
                                            color = VsCodeColors.Breadcrumb,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUp)
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Text("↑ ..", color = VsCodeColors.Breadcrumb, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (entries.isEmpty()) {
                        Text(
                            "Empty.",
                            color = VsCodeColors.Breadcrumb,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        LazyColumn {
                            items(entries) { entry ->
                                val isOpen = !entry.isDir && file != null &&
                                    file.path == if (path == ".") entry.name else "$path/${entry.name}"
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isOpen) VsCodeColors.HoverRow else Color.Transparent)
                                        .clickable {
                                            val next = if (path == ".") entry.name else "$path/${entry.name}"
                                            if (entry.isDir) onOpenDir(next) else onOpenFile(next)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (entry.isDir) "📁" else "📄", fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        entry.name,
                                        color = if (isOpen) VsCodeColors.LineNumberActive else VsCodeColors.Plain,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Editor ───────────────────────────────────────────────────
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                if (file == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a file to open it.", color = VsCodeColors.Breadcrumb, fontSize = 14.sp)
                    }
                } else {
                    val fileName = file.path.substringAfterLast('/')
                    // Tab bar — one active tab, matching VS Code's tab strip.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.TabBarBg),
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .background(VsCodeColors.ActiveTabBg)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📄", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(fileName, color = VsCodeColors.Plain, fontSize = 13.sp)
                            }
                            Box(Modifier.fillMaxWidth().height(2.dp).background(VsCodeColors.ActiveTabAccent))
                        }
                    }
                    // Breadcrumb — the full open-file path, VS Code style.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.EditorBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        val segments = file.path.split("/").filter { it.isNotEmpty() }
                        segments.forEachIndexed { i, segment ->
                            if (i > 0) {
                                Text(" › ", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                            }
                            Text(
                                segment,
                                color = if (i == segments.lastIndex) VsCodeColors.LineNumberActive else VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(VsCodeColors.Border))

                    // Gutter + syntax-highlighted source, one row per line.
                    val annotated = remember(file.content, file.language) { highlightCode(file.content, file.language) }
                    val lines = remember(file.content) { file.content.split("\n") }
                    val annotatedLines = remember(annotated, lines) {
                        var offset = 0
                        lines.map { line ->
                            val end = (offset + line.length).coerceAtMost(annotated.length)
                            val slice = if (offset <= end) annotated.subSequence(offset, end) else AnnotatedString("")
                            offset = end + 1
                            slice
                        }
                    }

                    // Find-in-file (spec §15) — purely client-side, the file's
                    // already fully loaded; no bridge round-trip needed.
                    var findQuery by remember(file.path) { mutableStateOf("") }
                    var findIndex by remember(file.path) { mutableStateOf(0) }
                    val findMatches = remember(findQuery, lines) {
                        if (findQuery.isBlank()) emptyList()
                        else lines.indices.filter { lines[it].contains(findQuery, ignoreCase = true) }
                    }
                    LaunchedEffect(findMatches) { findIndex = 0 }
                    val listState = rememberLazyListState()
                    LaunchedEffect(findIndex, findMatches) {
                        findMatches.getOrNull(findIndex)?.let { listState.scrollToItem(it, scrollOffset = -200) }
                    }
                    fun jump(delta: Int) {
                        if (findMatches.isEmpty()) return
                        findIndex = ((findIndex + delta) % findMatches.size + findMatches.size) % findMatches.size
                    }

                    // Symbol outline (spec §15) — same regex-based, no-parser
                    // spirit as highlightCode; jumps scroll the same LazyColumn
                    // find-in-file already uses.
                    val symbols = remember(file.content, file.language) { extractSymbols(file.content, file.language) }
                    var showOutline by remember(file.path) { mutableStateOf(false) }
                    var focusSymbol by remember(file.path) { mutableStateOf<CodeSymbol?>(null) }
                    val editorScope = rememberCoroutineScope()

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(VsCodeColors.EditorBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(200.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(VsCodeColors.SidebarBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (findQuery.isEmpty()) {
                                Text("🔎 Find in file…", color = VsCodeColors.Breadcrumb, fontSize = 12.sp)
                            }
                            BasicTextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                textStyle = TextStyle(color = VsCodeColors.Plain, fontSize = 12.sp),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(VsCodeColors.Plain),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { jump(1) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (findQuery.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (findMatches.isEmpty()) "no matches" else "${findIndex + 1}/${findMatches.size}",
                                color = VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("▲", color = LensCodeColors.Blue, fontSize = 13.sp, modifier = Modifier.clickable { jump(-1) })
                            Spacer(Modifier.width(10.dp))
                            Text("▼", color = LensCodeColors.Blue, fontSize = 13.sp, modifier = Modifier.clickable { jump(1) })
                        }
                        Spacer(Modifier.weight(1f))
                        if (symbols.isNotEmpty()) {
                            Text(
                                "🗂 Outline (${symbols.size})",
                                color = if (showOutline) LensCodeColors.Blue else VsCodeColors.Breadcrumb,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showOutline = !showOutline },
                            )
                        }
                    }

                    val currentMatchLine = findMatches.getOrNull(findIndex)
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp), state = listState) {
                            itemsIndexed(annotatedLines) { idx, lineText ->
                                val isMatch = idx in findMatches
                                val isCurrent = idx == currentMatchLine
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            when {
                                                isCurrent -> Color(0x552F81F7)
                                                isMatch -> Color(0x33F0C040)
                                                else -> Color.Transparent
                                            },
                                        ),
                                ) {
                                    Text(
                                        (idx + 1).toString(),
                                        color = VsCodeColors.LineNumber,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(44.dp).padding(end = 12.dp),
                                    )
                                    Text(lineText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        if (showOutline) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 8.dp)
                                    .width(260.dp)
                                    .heightIn(max = 320.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(VsCodeColors.SidebarBg)
                                    .border(1.dp, VsCodeColors.Border, RoundedCornerShape(3.dp))
                                    .padding(vertical = 6.dp),
                            ) {
                                LazyColumn {
                                    items(symbols) { sym ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showOutline = false
                                                    editorScope.launch { listState.scrollToItem(sym.line, scrollOffset = -100) }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                symbolKindIcon(sym.kind),
                                                fontSize = 11.sp,
                                                modifier = Modifier.width(20.dp),
                                            )
                                            Text(
                                                sym.name,
                                                color = VsCodeColors.Plain,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                "${sym.line + 1}",
                                                color = VsCodeColors.Breadcrumb,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            // Focus card (spec §16) — isolates just this symbol's
                                            // body, separate from "jump to it in the full file"
                                            // (clicking the row itself).
                                            Text(
                                                "⤢",
                                                color = LensCodeColors.Blue,
                                                fontSize = 13.sp,
                                                modifier = Modifier.clickable {
                                                    showOutline = false
                                                    focusSymbol = sym
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (focusSymbol != null) {
                            val range = remember(focusSymbol, lines, file.language) {
                                symbolBodyRange(lines, focusSymbol!!, file.language)
                            }
                            Box(
                                Modifier.fillMaxSize().background(Color(0xDD000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth(0.92f)
                                        .fillMaxHeight(0.88f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(VsCodeColors.EditorBg)
                                        .border(1.dp, VsCodeColors.Border, RoundedCornerShape(3.dp))
                                        .padding(16.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(symbolKindIcon(focusSymbol!!.kind), fontSize = 15.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            focusSymbol!!.name,
                                            color = VsCodeColors.Plain,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${focusSymbol!!.kind} · L${range.first + 1}–${range.last + 1}",
                                            color = VsCodeColors.Breadcrumb,
                                            fontSize = 12.sp,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "✕ Close",
                                            color = LensCodeColors.Muted,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { focusSymbol = null },
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(VsCodeColors.Border))
                                    Spacer(Modifier.height(6.dp))
                                    LazyColumn(Modifier.fillMaxSize()) {
                                        items((range.first..range.last).toList()) { idx ->
                                            Row(Modifier.fillMaxWidth()) {
                                                Text(
                                                    (idx + 1).toString(),
                                                    color = VsCodeColors.LineNumber,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.width(44.dp).padding(end = 12.dp),
                                                )
                                                Text(annotatedLines[idx], fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (file.truncated) {
                        Text(
                            "(truncated — file is larger than the preview limit)",
                            color = LensCodeColors.Amber,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                }
                // Claude Code–style composer, pinned under the editor (see AgentComposer).
                composer?.invoke()
            }
        }
    }
}

/** Icon for one outline entry's kind (spec §15) — same casual mapping as the file tree's 📁/📄. */
private fun symbolKindIcon(kind: String): String = when (kind) {
    "function" -> "ƒ"
    "class" -> "🄲"
    "interface" -> "🄸"
    "struct" -> "🅂"
    "enum" -> "🄴"
    "trait" -> "🅃"
    "impl" -> "▸"
    "type" -> "🅃"
    "method" -> "ƒ"
    else -> "•"
}
