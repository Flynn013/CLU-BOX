/*
 * Copyright 2026 Flynn013 / CLU/BOX
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.python.PythonBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "DynamicSchemaGenerator"

/**
 * Translates Python skill files into the JSON tool schemas expected by the
 * active LLM (Gemini Cloud or LiteRT-LM). Implements **Phase 3 — Dynamic
 * Schema Generation** of the bare-metal architecture.
 *
 * # Pipeline
 * 1. Walk `${filesDir}/skill_box/*.py` to discover dynamic skills authored at
 *    runtime (typically by the agent itself via [SplinterAPI.skillBoxWrite]).
 * 2. For every file, call [PythonBridge.introspectModule] which uses
 *    `inspect.signature` and `inspect.getdoc` to extract function names,
 *    docstrings and full type-annotated signatures.
 * 3. Translate each Python signature into a JSON schema fragment:
 *    ```json
 *    {
 *      "name": "search_wiki",
 *      "description": "Look up a Wikipedia article and return the lead.",
 *      "parameters": {
 *        "type": "object",
 *        "properties": {
 *          "query": {"type": "string", "description": "search term"}
 *        },
 *        "required": ["query"]
 *      }
 *    }
 *    ```
 * 4. Wrap them according to the active engine:
 *    - `AgentEngine.CLOUD` (Gemini): `{"functionDeclarations": [ ... ]}`
 *    - `AgentEngine.LOCAL`  (LiteRT-LM): `{"tools": [ ... ]}`
 *
 * # Type mapping
 * | Python annotation              | JSON schema type |
 * | ------------------------------ | ---------------- |
 * | `str`                          | `string`         |
 * | `int`                          | `integer`        |
 * | `float` / `decimal.Decimal`    | `number`         |
 * | `bool`                         | `boolean`        |
 * | `list[...]` / `List[...]`      | `array`          |
 * | `dict[...]` / `Dict[...]`      | `object`         |
 * | anything else                  | `string` (safe fallback) |
 *
 * The generator is intentionally conservative: when a hint cannot be parsed it
 * falls back to `"string"` so the LLM still sees a usable schema rather than
 * a hard failure.
 */
object DynamicSchemaGenerator {

  /** Produce a JSON schema document covering every `.py` file in the SKILL_BOX. */
  suspend fun buildSchema(
    context: Context,
    engine: AgentEngine,
  ): String {
    val skillDir = File(context.filesDir, "skill_box")
    if (!skillDir.isDirectory) return wrap(JSONArray(), engine)

    val files = skillDir.listFiles { f -> f.isFile && f.name.endsWith(".py") }
      ?.sortedBy { it.name } ?: return wrap(JSONArray(), engine)

    val declarations = JSONArray()
    for (f in files) {
      val triples = PythonBridge.introspectModule(f.absolutePath)
      for ((name, doc, sig) in triples) {
        if (name == "__error__") {
          Log.w(TAG, "skipping ${f.name}: $doc")
          continue
        }
        declarations.put(buildFunctionDeclaration(name, doc, sig))
      }
    }
    return wrap(declarations, engine)
  }

  /**
   * Translate a single `(name, doc, signature)` triple into a JSON schema
   * function declaration.
   *
   * `signature` is the raw output of Python's `str(inspect.signature(fn))`,
   * e.g. `"(query: str, limit: int = 5) -> str"`.
   */
  fun buildFunctionDeclaration(name: String, doc: String, signature: String): JSONObject {
    val params = parseSignature(signature)
    val properties = JSONObject()
    val required = JSONArray()
    params.forEach { p ->
      properties.put(p.name, JSONObject().apply {
        put("type", p.jsonType)
        if (p.description.isNotBlank()) put("description", p.description)
      })
      if (p.required) required.put(p.name)
    }
    return JSONObject().apply {
      put("name", name)
      put("description", doc.ifBlank { "User-defined skill: $name" })
      put("parameters", JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("required", required)
      })
    }
  }

  /** Parsed parameter description used to build the JSON schema. */
  data class ParsedParam(
    val name: String,
    val jsonType: String,
    val description: String,
    val required: Boolean,
  )

  /**
   * Tokenise a raw signature string like `"(query: str, limit: int = 5)"` into
   * a list of [ParsedParam]s, splitting on top-level commas and ignoring the
   * return-type annotation if present.
   */
  fun parseSignature(signature: String): List<ParsedParam> {
    // Strip surrounding parentheses and the optional `-> ReturnType` suffix.
    var s = signature.trim()
    val arrowIdx = s.indexOf(" -> ")
    if (arrowIdx >= 0) s = s.substring(0, arrowIdx)
    if (s.startsWith("(")) s = s.removePrefix("(")
    if (s.endsWith(")")) s = s.removeSuffix(")")
    if (s.isBlank()) return emptyList()

    val out = mutableListOf<ParsedParam>()
    splitTopLevel(s).forEach { token ->
      val t = token.trim()
      if (t.isEmpty() || t == "/" || t == "*") return@forEach
      // Skip `*args` and `**kwargs`.
      if (t.startsWith("*")) return@forEach

      val hasDefault = t.contains('=')
      val (head, _) = if (hasDefault) t.substringBefore('=') to t.substringAfter('=')
        else t to ""
      val (paramName, annotation) = if (head.contains(':')) {
        head.substringBefore(':').trim() to head.substringAfter(':').trim()
      } else head.trim() to ""

      out += ParsedParam(
        name = paramName,
        jsonType = mapAnnotation(annotation),
        description = if (annotation.isNotBlank()) "($annotation)" else "",
        required = !hasDefault,
      )
    }
    return out
  }

  /** Split a signature body on top-level commas, ignoring those inside brackets. */
  private fun splitTopLevel(body: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    body.forEach { ch ->
      when (ch) {
        '[', '(', '{', '<' -> { depth++; sb.append(ch) }
        ']', ')', '}', '>' -> { depth--; sb.append(ch) }
        ',' -> if (depth == 0) {
          out += sb.toString()
          sb.clear()
        } else sb.append(ch)
        else -> sb.append(ch)
      }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
  }

  /** Map a Python annotation string to a JSON schema type. */
  fun mapAnnotation(annotation: String): String {
    val a = annotation.trim().lowercase()
    return when {
      a.isEmpty() -> "string"
      a == "str" -> "string"
      a == "int" -> "integer"
      a == "float" || a.endsWith("decimal") -> "number"
      a == "bool" -> "boolean"
      a.startsWith("list") || a.startsWith("tuple") || a.startsWith("set") -> "array"
      a.startsWith("dict") || a.startsWith("mapping") -> "object"
      a.startsWith("optional[") || a.startsWith("union[") -> {
        // Strip wrapper and recurse on the first argument inside the brackets.
        val inner = a.substringAfter('[').removeSuffix("]").substringBefore(',').trim()
        mapAnnotation(inner)
      }
      else -> "string"
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Engine-specific wrappers
  // ─────────────────────────────────────────────────────────────────────────

  private fun wrap(decls: JSONArray, engine: AgentEngine): String = when (engine) {
    AgentEngine.CLOUD -> JSONObject().apply { put("functionDeclarations", decls) }.toString(2)
    AgentEngine.LOCAL -> JSONObject().apply { put("tools", decls) }.toString(2)
  }
}
