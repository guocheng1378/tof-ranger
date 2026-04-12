# ToF Ranger ProGuard Rules

# Keep sensor-related classes
-keep class com.example.tofranger.** { *; }

# Keep TTS
-keep class android.speech.tts.** { *; }
