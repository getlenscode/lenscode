package com.lenscode.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal regex-based tokenizer, styled after VS Code's Dark+ theme token
 * colors by default — or whichever theme [applyAppTheme] last selected. Not a
 * real parser (no per-language grammar, no nesting-aware string/comment
 * handling) — good enough for read-only file preview, which is all the Code
 * view does today.
 */
object VsCodeColors {
    var EditorBg by mutableStateOf(CURSOR_CODE.editorBg); private set
    var SidebarBg by mutableStateOf(CURSOR_CODE.sidebarBg); private set
    var TabBarBg by mutableStateOf(CURSOR_CODE.tabBarBg); private set
    var ActiveTabBg by mutableStateOf(CURSOR_CODE.activeTabBg); private set
    /** Theme-linked (not its own palette field) — always follows the app-chrome accent. */
    val ActiveTabAccent: Color get() = LensCodeColors.Cyan
    var LineNumber by mutableStateOf(CURSOR_CODE.lineNumber); private set
    val LineNumberActive: Color get() = LensCodeColors.Text
    val Border: Color get() = LensCodeColors.CardBorder
    var HoverRow by mutableStateOf(CURSOR_CODE.hoverRow); private set
    val Breadcrumb: Color get() = LensCodeColors.Muted
    var Keyword by mutableStateOf(CURSOR_CODE.keyword); private set
    var StringLit by mutableStateOf(CURSOR_CODE.stringLit); private set
    var Comment by mutableStateOf(CURSOR_CODE.comment); private set
    var Number by mutableStateOf(CURSOR_CODE.number); private set
    var TypeName by mutableStateOf(CURSOR_CODE.typeName); private set
    var Plain by mutableStateOf(CURSOR_CODE.plain); private set

    fun apply(p: CodePalette) {
        EditorBg = p.editorBg; SidebarBg = p.sidebarBg; TabBarBg = p.tabBarBg; ActiveTabBg = p.activeTabBg
        LineNumber = p.lineNumber; HoverRow = p.hoverRow
        Keyword = p.keyword; StringLit = p.stringLit; Comment = p.comment
        Number = p.number; TypeName = p.typeName; Plain = p.plain
    }
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

/** One entry in the symbol outline (spec §15) — 0-based line for scroll targeting. */
data class CodeSymbol(val name: String, val kind: String, val line: Int)

/** name-capturing regex, tagged with the outline kind it represents. */
private data class SymbolPattern(val regex: Regex, val kind: String)

/**
 * Regex-based symbol extraction, same "good enough for read-only preview,
 * not a real parser" spirit as [highlightCode] — no AST, no LSP. Good enough
 * to jump around a file by its top-level shape; not guaranteed exhaustive on
 * unusual formatting.
 */
private val SYMBOL_PATTERNS: Map<String, List<SymbolPattern>> = mapOf(
    "typescript" to listOf(
        SymbolPattern(Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)"""), "function"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?class\s+(\w+)"""), "class"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?interface\s+(\w+)"""), "interface"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?type\s+(\w+)\s*="""), "type"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?const\s+(\w+)\s*=\s*(?:async\s*)?\("""), "function"),
    ),
    "javascript" to listOf(
        SymbolPattern(Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)"""), "function"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?class\s+(\w+)"""), "class"),
        SymbolPattern(Regex("""^\s*(?:export\s+)?const\s+(\w+)\s*=\s*(?:async\s*)?\("""), "function"),
    ),
    "python" to listOf(
        SymbolPattern(Regex("""^\s*def\s+(\w+)"""), "function"),
        SymbolPattern(Regex("""^\s*class\s+(\w+)"""), "class"),
    ),
    "rust" to listOf(
        SymbolPattern(Regex("""^\s*(?:pub\s+)?(?:async\s+)?fn\s+(\w+)"""), "function"),
        SymbolPattern(Regex("""^\s*(?:pub\s+)?struct\s+(\w+)"""), "struct"),
        SymbolPattern(Regex("""^\s*(?:pub\s+)?enum\s+(\w+)"""), "enum"),
        SymbolPattern(Regex("""^\s*(?:pub\s+)?trait\s+(\w+)"""), "trait"),
        SymbolPattern(Regex("""^\s*impl(?:<[^>]*>)?\s+(?:\w+\s+for\s+)?(\w+)"""), "impl"),
    ),
    "go" to listOf(
        SymbolPattern(Regex("""^func\s+(?:\([^)]*\)\s*)?(\w+)"""), "function"),
        SymbolPattern(Regex("""^type\s+(\w+)\s+struct"""), "struct"),
        SymbolPattern(Regex("""^type\s+(\w+)\s+interface"""), "interface"),
    ),
    "c" to listOf(
        SymbolPattern(Regex("""^\s*struct\s+(\w+)\s*\{"""), "struct"),
        SymbolPattern(Regex("""^\w[\w\s*]*?\s(\w+)\s*\([^;]*\)\s*\{"""), "function"),
    ),
    "cpp" to listOf(
        SymbolPattern(Regex("""^\s*class\s+(\w+)"""), "class"),
        SymbolPattern(Regex("""^\s*struct\s+(\w+)\s*\{"""), "struct"),
        SymbolPattern(Regex("""^\w[\w\s*:<>]*?\s(\w+)\s*\([^;]*\)\s*\{"""), "function"),
    ),
    "csharp" to listOf(
        SymbolPattern(Regex("""^\s*(?:public|private|protected|internal)?\s*(?:static\s+)?class\s+(\w+)"""), "class"),
        SymbolPattern(Regex("""^\s*(?:public|private|protected|internal)?\s*(?:static\s+)?interface\s+(\w+)"""), "interface"),
        SymbolPattern(Regex("""^\s*(?:public|private|protected|internal)\s+[\w<>\[\]]+\s+(\w+)\s*\("""), "method"),
    ),
)

/** Extracts an outline of top-level definitions, in source order. */
fun extractSymbols(code: String, language: String?): List<CodeSymbol> {
    val patterns = SYMBOL_PATTERNS[language] ?: return emptyList()
    val symbols = mutableListOf<CodeSymbol>()
    code.split("\n").forEachIndexed { idx, line ->
        for (p in patterns) {
            val m = p.regex.find(line) ?: continue
            val name = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: continue
            symbols.add(CodeSymbol(name, p.kind, idx))
            break // one symbol per line, first pattern wins
        }
    }
    return symbols
}

private val BRACE_LANGS = setOf("typescript", "javascript", "rust", "go", "c", "cpp", "csharp")

/**
 * Code focus cards (spec §16): isolates one symbol's body by brace-matching
 * (curly-brace languages) or indentation (Python) — heuristic, not a real
 * parser, so pathological formatting can under/over-shoot; callers should
 * treat the result as "best effort," same spirit as [highlightCode].
 */
fun symbolBodyRange(lines: List<String>, symbol: CodeSymbol, language: String?): IntRange {
    if (symbol.line !in lines.indices) return symbol.line..symbol.line
    return when {
        language == "python" -> pythonBlockRange(lines, symbol.line)
        language in BRACE_LANGS -> braceBlockRange(lines, symbol.line, language)
        else -> symbol.line..symbol.line
    }
}

private fun stripStringsAndComments(line: String, language: String?): String {
    var s = Regex(commentPattern(language)).replace(line, "")
    s = Regex(STRING_RE).replace(s, "\"\"")
    return s
}

private fun braceBlockRange(lines: List<String>, startLine: Int, language: String?): IntRange {
    var depth = 0
    var seenOpen = false
    var end = startLine
    for (i in startLine until lines.size) {
        for (ch in stripStringsAndComments(lines[i], language)) {
            if (ch == '{') { depth++; seenOpen = true } else if (ch == '}') depth--
        }
        end = i
        if (seenOpen && depth <= 0) break
        if (i - startLine > 500) break // safety cap against a runaway match
    }
    return startLine..end
}

private fun pythonBlockRange(lines: List<String>, startLine: Int): IntRange {
    val baseIndent = lines[startLine].takeWhile { it == ' ' || it == '\t' }.length
    var end = startLine
    for (i in (startLine + 1) until lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            end = i
            continue
        }
        val indent = line.takeWhile { it == ' ' || it == '\t' }.length
        if (indent <= baseIndent) break
        end = i
    }
    return startLine..end
}
