package com.google.ai.edge.gallery.customtasks.agentchat

/** Selects which inference backend the agentic loop should use. */
enum class AgentEngine {
  /** On-device LiteRT-LM model (Gemma 4 E2B/E4B). */
  LOCAL,
  /** Gemini Cloud API model. */
  CLOUD,
}
