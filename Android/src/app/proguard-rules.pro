# ============================================================================
# CLU/BOX ProGuard / R8 Rules
# ============================================================================

# --- MediaPipe Tasks ---
# Prevent stripping/obfuscation of MediaPipe classes used for on-device inference.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# --- LiteRT LM (litertlm) ---
# The LiteRT LM engine uses reflection for tool/conversation APIs.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# --- Google AI Edge Gallery runtime ---
# Keep runtime helper interfaces and LLM model classes that are resolved dynamically.
-keep class com.google.ai.edge.gallery.runtime.** { *; }

# --- Tool annotations used by the Agent Chat tool-calling system ---
# The litertlm tool() reflection scans for @Tool and @ToolParam annotations.
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool *;
    @com.google.ai.edge.litertlm.ToolParam *;
}

# --- TensorFlow Lite / LiteRT native bindings ---
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
