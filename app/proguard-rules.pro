# ToF Ranger ProGuard Rules

# Keep all app classes (small app, no need to strip)
-keep class com.example.tofranger.** { *; }

# Keep sensor-related Android classes
-keep class android.hardware.** { *; }

# Suppress warnings for optional dependencies
-dontwarn android.speech.tts.**
