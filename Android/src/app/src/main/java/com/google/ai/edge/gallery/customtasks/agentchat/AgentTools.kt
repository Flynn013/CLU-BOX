package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AgentTools {

    private const val MAX_TOOL_OUTPUT_CHARS = 3000

    /**
     * The MTE Crash Killer:
     * Hard-caps tool outputs. If the output exceeds the safe buffer limit, it truncates the string 
     * and saves the full output to the internal sandbox to prevent KV Cache bloat and SIGSEGV faults.
     */
    fun capOutputWithSpill(context: Context, rawOutput: String, toolName: String): String {
        if (rawOutput.length <= MAX_TOOL_OUTPUT_CHARS) {
            return rawOutput
        }

        // Generate a spillway file in the internal workspace
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val spillFileName = "spill_${toolName}_$timeStamp.txt"
        val workspaceDir = File(context.filesDir, "workspace/temp_out")
        
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        val spillFile = File(workspaceDir, spillFileName)
        
        return try {
            spillFile.writeText(rawOutput)
            val truncated = rawOutput.substring(0, MAX_TOOL_OUTPUT_CHARS)
            """
            $truncated
            
            [SYSTEM WARNING: Output truncated to prevent memory fault.]
            [Full output saved to: /workspace/temp_out/$spillFileName]
            """.trimIndent()
        } catch (e: Exception) {
            val truncated = rawOutput.substring(0, MAX_TOOL_OUTPUT_CHARS)
            "$truncated\n\n[SYSTEM WARNING: Output truncated. Failed to write spill file: ${e.message}]"
        }
    }

    // Example implementation for your FileBoxRead tool using the spillway
    fun readFileBox(context: Context, filePath: String): String {
        val targetFile = File(File(context.filesDir, "workspace"), filePath)
        if (!targetFile.exists()) return "[Error: File not found]"
        
        val content = targetFile.readText()
        return capOutputWithSpill(context, content, "readFile")
    }
}
