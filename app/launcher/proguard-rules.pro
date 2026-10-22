# Shart TV launcher — R8 full mode. Obfuscate + shrink aggressively; keep only what reflection needs.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# Activities are kept via the merged manifest. org.json ships in android.jar (not shrunk).
# Nothing in this app is accessed by name from outside, so no extra -keep is required.
-dontwarn org.jetbrains.annotations.**
