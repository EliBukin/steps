# Room, WorkManager, DataStore and Compose ship their own consumer ProGuard rules (Room's keeps the
# generated StepSplitDatabase_Impl it finds by name), so nothing of the app's own needs keeping here:
# R8 may shrink and rename all of it, unused database code included.
# Kotlin coroutines' debug metadata is safe to strip.
