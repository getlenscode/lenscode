package com.lenscode.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Something the user attached to the prompt in the Code view's composer. */
sealed class ComposerAttachment {
    /** A file in the open project, by absolute path. */
    data class File(val path: String) : ComposerAttachment()

    /** A tmux session on the agent's machine, by name. */
    data class Tmux(val session: String) : ComposerAttachment()

    /** Short text for the chip: the file name, or the session name. */
    val label: String
        get() = when (this) {
            is File -> path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
            is Tmux -> session
        }
}

/**
 * The prompt with a "Context:" block appended for [attachments] and the editor's [openFilePaths]
 * (empty when the open-files checkbox is off). Paths are listed once; with nothing to add, the
 * prompt is returned unchanged.
 */
fun buildPromptWithContext(
    prompt: String,
    attachments: List<ComposerAttachment>,
    openFilePaths: List<String>,
): String {
    val attachedFiles = attachments.filterIsInstance<ComposerAttachment.File>().map { it.path }
    val open = openFilePaths.distinct().filter { it !in attachedFiles }
    val files = attachedFiles.distinct()
    val sessions = attachments.filterIsInstance<ComposerAttachment.Tmux>().map { it.session }.distinct()
    if (open.isEmpty() && files.isEmpty() && sessions.isEmpty()) return prompt

    val lines = mutableListOf<String>()
    if (open.isNotEmpty()) lines += "- Open in the editor: ${open.joinToString(", ")}"
    for (path in files) lines += "- Attached file: $path"
    for (session in sessions) {
        lines += "- Attached tmux session: $session — read it with `tmux capture-pane -t $session -p`, " +
            "and type into it with `tmux send-keys -t $session \"<command>\" Enter`"
    }
    return prompt.trimEnd() + "\n\nContext:\n" + lines.joinToString("\n")
}

/** [text] inserted at the cursor (replacing any selection), with the cursor left after it. */
fun insertAtCursor(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(0, value.text.length)
    val next = value.text.substring(0, start) + text + value.text.substring(end)
    return TextFieldValue(next, TextRange(start + text.length))
}

/** [relative] under the project [root] as an absolute path; unchanged when already absolute or the root is unknown. */
fun absoluteProjectPath(root: String?, relative: String): String {
    if (relative.startsWith("/") || root.isNullOrBlank()) return relative
    return root.trimEnd('/') + "/" + relative.trimStart('/')
}
