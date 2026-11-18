# R8 rules for the release build. Libraries (OkHttp, Room, WorkManager, Vico, AndroidX) ship their own.

# Readable crash reports: keep line numbers, hide the original file names.
# Stack traces are de-obfuscated with the mapping.txt that the release workflow keeps for each version.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Our enums are stored and exchanged by name (Room, the server, SharedPreferences), so keep the names.
-keep enum com.company.supportvisit.** { *; }

# Signature pad (old library without consumer rules); its view is inflated from XML.
-keep class com.github.gcacace.signaturepad.** { *; }
-dontwarn com.github.gcacace.signaturepad.**

# Release builds: drop verbose and debug logging.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
