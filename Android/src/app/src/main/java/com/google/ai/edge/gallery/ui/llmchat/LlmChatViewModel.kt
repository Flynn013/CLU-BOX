package com.google.ai.edge.gallery.ui.llmchat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class LlmChatViewModel : ViewModel() {

    // Assuming you have a state tracking the chat history
    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory

    // Hard limit to prevent MTE allocation failures on Pixel 10 Pro
    private val MAX_SAFE_HISTORY_MESSAGES = 10 

    /**
     * The Context Breaker:
     * Prunes the conversation history to keep the KV cache strictly within the 
     * safe bounds of the Mustang kernel memory tagger.
     */
    fun enforceMemorySafetyBounds() {
        _chatHistory.update { history ->
            if (history.size > MAX_SAFE_HISTORY_MESSAGES) {
                // Keep the original system prompt (index 0) and the most recent messages
                val systemPrompt = history.firstOrNull { it.isSystem }
                val recentMessages = history.takeLast(MAX_SAFE_HISTORY_MESSAGES - 1)
                
                val prunedHistory = mutableListOf<ChatMessage>()
                if (systemPrompt != null) prunedHistory.add(systemPrompt)
                prunedHistory.add(ChatMessage(
                    text = "[System Note: Older context compressed to protect memory bounds.]",
                    isSystem = true
                ))
                prunedHistory.addAll(recentMessages)
                
                prunedHistory
            } else {
                history
            }
        }
    }
    
    // Call enforceMemorySafetyBounds() immediately before formatting the prompt for the JNI execution.
}

data class ChatMessage(val text: String, val isSystem: Boolean = false)
