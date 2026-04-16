/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.osmodules

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// ── Syntax highlighting colours (Acode-inspired dark palette) ───────────

private val colorKeyword = Color(0xFFFF79C6)   // pink
private val colorString = Color(0xFFF1FA8C)    // yellow
private val colorComment = Color(0xFF6272A4)   // muted blue-grey
private val colorNumber = Color(0xFFBD93F9)    // purple
private val colorType = Color(0xFF8BE9FD)      // cyan
private val colorFunction = Color(0xFF50FA7B)  // green
private val colorOperator = Color(0xFFFF5555)  // red
private val colorDefault = Color(0xFFF8F8F2)   // off-white

// ── Language keyword sets ────────────────────────────────────────────────

private val kotlinKeywords = setOf(
  "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
  "companion", "const", "constructor", "continue", "crossinline", "data",
  "do", "else", "enum", "expect", "external", "false", "final", "finally",
  "for", "fun", "get", "if", "import", "in", "infix", "init", "inline",
  "inner", "interface", "internal", "is", "it", "lateinit", "lazy", "noinline",
  "null", "object", "open", "operator", "out", "override", "package",
  "private", "protected", "public", "reified", "return", "sealed", "set",
  "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias",
  "val", "var", "vararg", "when", "where", "while",
)

private val kotlinTypes = setOf(
  "Any", "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long",
  "Nothing", "Short", "String", "Unit", "Array", "List", "Map", "Set",
  "MutableList", "MutableMap", "MutableSet", "Pair", "Triple",
)

private val pythonKeywords = setOf(
  "False", "None", "True", "and", "as", "assert", "async", "await",
  "break", "class", "continue", "def", "del", "elif", "else", "except",
  "finally", "for", "from", "global", "if", "import", "in", "is",
  "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
  "while", "with", "yield",
)

private val jsKeywords = setOf(
  "async", "await", "break", "case", "catch", "class", "const",
  "continue", "debugger", "default", "delete", "do", "else", "export",
  "extends", "false", "finally", "for", "from", "function", "if",
  "import", "in", "instanceof", "let", "new", "null", "of", "return",
  "static", "super", "switch", "this", "throw", "true", "try",
  "typeof", "undefined", "var", "void", "while", "with", "yield",
)

private val jsTypes = setOf(
  "Array", "Boolean", "Date", "Error", "Function", "Map", "Number",
  "Object", "Promise", "RegExp", "Set", "String", "Symbol", "WeakMap",
  "WeakSet", "console", "document", "window",
)

// ── Public API ──────────────────────────────────────────────────────────

/**
 * Returns a syntax-highlighted [AnnotatedString] for [code] based on
 * the file [extension] (e.g. "kt", "py", "js").
 *
 * Supported languages: Kotlin/Java, Python, JavaScript/TypeScript, HTML/XML,
 * CSS, JSON, Shell/Bash, Markdown. Falls back to plain monochrome text for
 * unrecognised extensions.
 */
fun highlightSyntax(code: String, extension: String): AnnotatedString {
  return when (extension.lowercase()) {
    "kt", "kts", "java", "scala", "groovy" -> highlightCLike(code, kotlinKeywords, kotlinTypes, "//", "/*", "*/")
    "py", "rb" -> highlightHash(code, pythonKeywords)
    "js", "jsx", "ts", "tsx" -> highlightCLike(code, jsKeywords, jsTypes, "//", "/*", "*/")
    "html", "htm", "xml", "svg" -> highlightMarkup(code)
    "css" -> highlightCss(code)
    "json" -> highlightJson(code)
    "sh", "bash", "zsh" -> highlightHash(code, emptySet())
    "md" -> highlightMarkdown(code)
    "rs" -> highlightCLike(code, setOf("fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "match", "if", "else", "for", "while", "loop", "return", "self", "super", "true", "false", "as", "const", "static", "ref", "type", "where", "async", "await", "move", "unsafe", "extern", "crate"), setOf("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result", "Box"), "//", "/*", "*/")
    "go" -> highlightCLike(code, setOf("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var"), setOf("int", "int8", "int16", "int32", "int64", "uint", "float32", "float64", "bool", "string", "byte", "rune", "error"), "//", "/*", "*/")
    "c", "cpp", "h", "hpp", "cs" -> highlightCLike(code, setOf("auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "class", "namespace", "public", "private", "protected", "virtual", "override", "template", "typename", "using", "new", "delete", "this", "throw", "try", "catch", "nullptr", "true", "false", "include", "define", "ifdef", "ifndef", "endif"), emptySet(), "//", "/*", "*/")
    else -> buildAnnotatedString {
      pushStyle(SpanStyle(color = colorDefault))
      append(code)
      pop()
    }
  }
}

// ── C-like language highlighter (Kotlin, Java, JS, TS, Rust, Go, C++) ──

private fun highlightCLike(
  code: String,
  keywords: Set<String>,
  types: Set<String>,
  lineComment: String,
  blockStart: String,
  blockEnd: String,
): AnnotatedString = buildAnnotatedString {
  var i = 0
  val len = code.length

  while (i < len) {
    // Block comment.
    if (code.startsWith(blockStart, i)) {
      val end = code.indexOf(blockEnd, i + blockStart.length)
      val closeIdx = if (end < 0) len else end + blockEnd.length
      pushStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // Line comment.
    if (code.startsWith(lineComment, i)) {
      val end = code.indexOf('\n', i)
      val closeIdx = if (end < 0) len else end
      pushStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // String literals (double-quote and single-quote).
    if (code[i] == '"' || code[i] == '\'') {
      val quote = code[i]
      val start = i
      i++ // skip opening quote
      while (i < len && code[i] != quote) {
        if (code[i] == '\\' && i + 1 < len) i++ // skip escaped char
        i++
      }
      if (i < len) i++ // skip closing quote
      pushStyle(SpanStyle(color = colorString))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Numbers.
    if (code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit())) {
      val start = i
      while (i < len && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
      pushStyle(SpanStyle(color = colorNumber))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Words (identifiers / keywords / types).
    if (code[i].isLetter() || code[i] == '_') {
      val start = i
      while (i < len && (code[i].isLetterOrDigit() || code[i] == '_')) i++
      val word = code.substring(start, i)
      val style = when {
        word in keywords -> SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold)
        word in types -> SpanStyle(color = colorType)
        // Heuristic: word followed by '(' is likely a function call.
        i < len && code[i] == '(' -> SpanStyle(color = colorFunction)
        else -> SpanStyle(color = colorDefault)
      }
      pushStyle(style)
      append(word)
      pop()
      continue
    }

    // Operators.
    if (code[i] in "+-*/%=<>!&|^~?:") {
      pushStyle(SpanStyle(color = colorOperator))
      append(code[i])
      pop()
      i++
      continue
    }

    // Everything else.
    pushStyle(SpanStyle(color = colorDefault))
    append(code[i])
    pop()
    i++
  }
}

// ── Hash-comment languages (Python, Ruby, Shell) ────────────────────────

private fun highlightHash(code: String, keywords: Set<String>): AnnotatedString = buildAnnotatedString {
  var i = 0
  val len = code.length

  while (i < len) {
    // Comment.
    if (code[i] == '#') {
      val end = code.indexOf('\n', i)
      val closeIdx = if (end < 0) len else end
      pushStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // Triple-quoted strings.
    if (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) {
      val delim = code.substring(i, i + 3)
      val end = code.indexOf(delim, i + 3)
      val closeIdx = if (end < 0) len else end + 3
      pushStyle(SpanStyle(color = colorString))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // String literals.
    if (code[i] == '"' || code[i] == '\'') {
      val quote = code[i]
      val start = i
      i++
      while (i < len && code[i] != quote && code[i] != '\n') {
        if (code[i] == '\\' && i + 1 < len) i++
        i++
      }
      if (i < len && code[i] == quote) i++
      pushStyle(SpanStyle(color = colorString))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Numbers.
    if (code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit())) {
      val start = i
      while (i < len && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
      pushStyle(SpanStyle(color = colorNumber))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Words.
    if (code[i].isLetter() || code[i] == '_') {
      val start = i
      while (i < len && (code[i].isLetterOrDigit() || code[i] == '_')) i++
      val word = code.substring(start, i)
      val style = when {
        word in keywords -> SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold)
        i < len && code[i] == '(' -> SpanStyle(color = colorFunction)
        else -> SpanStyle(color = colorDefault)
      }
      pushStyle(style)
      append(word)
      pop()
      continue
    }

    pushStyle(SpanStyle(color = colorDefault))
    append(code[i])
    pop()
    i++
  }
}

// ── Markup (HTML/XML) ───────────────────────────────────────────────────

private fun highlightMarkup(code: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  val len = code.length

  while (i < len) {
    // HTML comment.
    if (code.startsWith("<!--", i)) {
      val end = code.indexOf("-->", i + 4)
      val closeIdx = if (end < 0) len else end + 3
      pushStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // Tags.
    if (code[i] == '<') {
      val start = i
      val end = code.indexOf('>', i)
      val closeIdx = if (end < 0) len else end + 1
      pushStyle(SpanStyle(color = colorKeyword))
      append(code.substring(start, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    pushStyle(SpanStyle(color = colorDefault))
    append(code[i])
    pop()
    i++
  }
}

// ── CSS ─────────────────────────────────────────────────────────────────

private fun highlightCss(code: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  val len = code.length

  while (i < len) {
    // Block comment.
    if (code.startsWith("/*", i)) {
      val end = code.indexOf("*/", i + 2)
      val closeIdx = if (end < 0) len else end + 2
      pushStyle(SpanStyle(color = colorComment, fontStyle = FontStyle.Italic))
      append(code.substring(i, closeIdx))
      pop()
      i = closeIdx
      continue
    }

    // Selectors / properties (simple heuristic: words before '{' or ':').
    if (code[i] == '{' || code[i] == '}' || code[i] == ';') {
      pushStyle(SpanStyle(color = colorOperator))
      append(code[i])
      pop()
      i++
      continue
    }

    // Strings.
    if (code[i] == '"' || code[i] == '\'') {
      val quote = code[i]
      val start = i
      i++
      while (i < len && code[i] != quote) i++
      if (i < len) i++
      pushStyle(SpanStyle(color = colorString))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Numbers and units.
    if (code[i].isDigit()) {
      val start = i
      while (i < len && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '%')) i++
      pushStyle(SpanStyle(color = colorNumber))
      append(code.substring(start, i))
      pop()
      continue
    }

    pushStyle(SpanStyle(color = colorDefault))
    append(code[i])
    pop()
    i++
  }
}

// ── JSON ────────────────────────────────────────────────────────────────

private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  val len = code.length

  while (i < len) {
    // Strings (keys and values).
    if (code[i] == '"') {
      val start = i
      i++
      while (i < len && code[i] != '"') {
        if (code[i] == '\\' && i + 1 < len) i++
        i++
      }
      if (i < len) i++
      // Keys are followed by ':'.
      val peek = code.substring(i).trimStart()
      val isKey = peek.startsWith(":")
      pushStyle(SpanStyle(color = if (isKey) colorType else colorString))
      append(code.substring(start, i))
      pop()
      continue
    }

    // Numbers, booleans, null.
    if (code[i].isDigit() || code[i] == '-') {
      val start = i
      if (code[i] == '-') i++
      while (i < len && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || code[i] == 'E' || code[i] == '+' || code[i] == '-')) i++
      pushStyle(SpanStyle(color = colorNumber))
      append(code.substring(start, i))
      pop()
      continue
    }

    if (code.startsWith("true", i) || code.startsWith("false", i) || code.startsWith("null", i)) {
      val word = when {
        code.startsWith("true", i) -> "true"
        code.startsWith("false", i) -> "false"
        else -> "null"
      }
      pushStyle(SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold))
      append(word)
      pop()
      i += word.length
      continue
    }

    pushStyle(SpanStyle(color = colorDefault))
    append(code[i])
    pop()
    i++
  }
}

// ── Markdown ────────────────────────────────────────────────────────────

private fun highlightMarkdown(code: String): AnnotatedString = buildAnnotatedString {
  val lines = code.lines()
  for ((index, line) in lines.withIndex()) {
    when {
      line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ") -> {
        pushStyle(SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold))
        append(line)
        pop()
      }
      line.startsWith("```") -> {
        pushStyle(SpanStyle(color = colorComment))
        append(line)
        pop()
      }
      line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
        pushStyle(SpanStyle(color = colorFunction))
        append(line)
        pop()
      }
      else -> {
        pushStyle(SpanStyle(color = colorDefault))
        append(line)
        pop()
      }
    }
    if (index < lines.lastIndex) {
      append("\n")
    }
  }
}
