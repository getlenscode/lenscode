package dev.aura.xr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal regex-based tokenizer, styled after VS Code's Dark+ theme token
 * colors. Not a real parser (no per-language grammar, no nesting-aware
 * string/comment handling) — good enough for read-only file preview, which is
 * all the Code view does today.
 */
object VsCodeColors {
    val EditorBg = Color(0xFF1E1E1E)
    val SidebarBg = Color(0xFF252526)
    val TabBarBg = Color(0xFF252526)
    val ActiveTabBg = Color(0xFF1E1E1E)
    val ActiveTabAccent = Color(0xFF007ACC)
    val LineNumber = Color(0xFF858585)
    val LineNumberActive = Color(0xFFC6C6C6)
    val Border = Color(0xFF3C3C3C)
    val HoverRow = Color(0xFF2A2D2E)
    val Breadcrumb = Color(0xFF9D9D9D)
    val Keyword = Color(0xFF569CD6)
    val StringLit = Color(0xFFCE9178)
    val Comment = Color(0xFF6A9955)
    val Number = Color(0xFFB5CEA8)
    val TypeName = Color(0xFF4EC9B0)
    val Plain = Color(0xFFD4D4D4)
}

private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "typescript" to setOf(
        "import", "export", "from", "const", "let", "var", "function", "return", "if", "else", "for", "while",
        "class", "interface", "type", "extends", "implements", "new", "this", "async", "await", "try", "catch",
        "finally", "throw", "switch", "case", "break", "continue", "default", "public", "private", "protected",
        "readonly", "static", "void", "null", "undefined", "true", "false", "enum", "namespace", "as", "in", "of",
        "typeof", "instanceof", "yield", "delete",
    ),
    "javascript" to setOf(
        "import", "export", "from", "const", "let", "var", "function", "return", "if", "else", "for", "while",
        "class", "extends", "new", "this", "async", "await", "try", "catch", "finally", "throw", "switch", "case",
        "break", "continue", "default", "typeof", "instanceof", "yield", "delete", "null", "undefined", "true",
        "false", "in", "of",
    ),
    "python" to setOf(
        "import", "from", "as", "def", "return", "if", "elif", "else", "for", "while", "class", "try", "except",
        "finally", "raise", "with", "lambda", "yield", "pass", "break", "continue", "and", "or", "not", "in", "is",
        "None", "True", "False", "global", "nonlocal", "async", "await", "self",
    ),
    "rust" to setOf(
        "fn", "let", "mut", "pub", "use", "mod", "struct", "enum", "impl", "trait", "for", "while", "loop", "if",
        "else", "match", "return", "break", "continue", "self", "Self", "true", "false", "as", "dyn", "async",
        "await", "move", "ref", "where", "unsafe", "crate", "super", "const", "static",
    ),
    "go" to setOf(
        "package", "import", "func", "return", "if", "else", "for", "range", "switch", "case", "default", "break",
        "continue", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "select", "true",
        "false", "nil",
    ),
    "c" to setOf(
        "int", "char", "float", "double", "void", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "struct", "union", "enum", "typedef", "const", "static", "extern", "sizeof",
        "unsigned", "signed", "long", "short", "include", "define",
    ),
    "cpp" to setOf(
        "int", "char", "float", "double", "void", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "struct", "class", "union", "enum", "typedef", "const", "static", "extern",
        "sizeof", "unsigned", "signed", "long", "short", "namespace", "using", "public", "private", "protected",
        "virtual", "override", "template", "typename", "new", "delete", "this", "true", "false", "nullptr", "auto",
        "include",
    ),
    "csharp" to setOf(
        "using", "namespace", "class", "public", "private", "protected", "internal", "static", "void", "int",
        "string", "bool", "var", "if", "else", "for", "foreach", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "new", "this", "true", "false", "null", "try", "catch", "finally", "throw",
        "interface", "struct", "enum", "override", "virtual", "async", "await",
    ),
    "json" to setOf("true", "false", "null"),
    "yaml" to setOf("true", "false", "null"),
    "shell" to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "function", "return", "echo", "export",
        "case", "esac", "in",
    ),
    "toml" to setOf("true", "false"),
)

private val STRING_RE = "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`"
private val NUMBER_RE = "\\b\\d+(?:\\.\\d+)?\\b"
private val WORD_RE = "\\b[A-Za-z_][A-Za-z0-9_]*\\b"

private fun commentPattern(language: String?): String = when (language) {
    "python", "shell", "toml", "yaml" -> "#.*"
    else -> "//.*"
}

/** Tokenizes and colors [code] for [language]; unrecognized languages fall back to plain text. */
fun highlightCode(code: String, language: String?): AnnotatedString {
    val keywords = KEYWORDS[language] ?: emptySet()
    val tokenRegex = Regex("${commentPattern(language)}|$STRING_RE|$NUMBER_RE|$WORD_RE")
    val commentRe = Regex(commentPattern(language))
    val stringRe = Regex(STRING_RE)
    val numberRe = Regex(NUMBER_RE)

    return buildAnnotatedString {
        var last = 0
        for (m in tokenRegex.findAll(code)) {
            if (m.range.first > last) append(code.substring(last, m.range.first))
            val text = m.value
            val style = when {
                commentRe.matches(text) -> SpanStyle(color = VsCodeColors.Comment)
                stringRe.matches(text) -> SpanStyle(color = VsCodeColors.StringLit)
                numberRe.matches(text) -> SpanStyle(color = VsCodeColors.Number)
                text in keywords -> SpanStyle(color = VsCodeColors.Keyword, fontWeight = FontWeight.Medium)
                text.isNotEmpty() && text[0].isUpperCase() -> SpanStyle(color = VsCodeColors.TypeName)
                else -> SpanStyle(color = VsCodeColors.Plain)
            }
            withStyle(style) { append(text) }
            last = m.range.last + 1
        }
        if (last < code.length) append(code.substring(last))
    }
}
