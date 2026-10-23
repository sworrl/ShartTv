# Shart TV launcher — R8 full mode. Obfuscate + shrink aggressively; keep only what reflection needs.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# Activities are kept via the merged manifest. org.json ships in android.jar (not shrunk).
# Nothing is accessed by name from outside; the one by-name use inside is kept below.
-dontwarn org.jetbrains.annotations.**

# The weather cache snapshots Wx's public fields by name (WeatherEngine.snapshot/restore).
-keepclassmembers class com.sharttv.launcher.weather.Wx { public <fields>; }
