# Room, WorkManager, DataStore and Compose ship their own consumer ProGuard rules.
# Kotlin coroutines' debug metadata is safe to strip; nothing app-specific needs keeping here.

# Keep Room-generated implementation classes discoverable by reflection-free codegen.
-keep class com.example.stepsplit.data.local.** { *; }
