# Add project specific ProGuard rules here.

# Keep custom views (used via XML or reflection in some Android versions)
-keep class com.example.tofranger.view.** { *; }

# Keep DataRecorder inner class for CSV export
-keep class com.example.tofranger.DataRecorder$DataPoint { *; }
-keep class com.example.tofranger.DataRecorder$ExportCallback { *; }

# Keep sensor listener interface
-keep class com.example.tofranger.SensorController$ToFListener { *; }
