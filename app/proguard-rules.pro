# Manifest components are kept automatically. Nothing reflective in this app.
-keep class com.akoya.home.dream.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep real class and method names. Unused code is still removed and the rest is still
# optimised; this only skips the renaming, so the baseline profile's rules match the code
# and crash reports are readable.
-dontobfuscate
