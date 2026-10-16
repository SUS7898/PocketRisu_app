# --- kotlinx.serialization -----------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.sus7898.pocketrisu.**$$serializer { *; }
-keepclassmembers class com.sus7898.pocketrisu.** { *** Companion; }
-keepclasseswithmembers class com.sus7898.pocketrisu.** { kotlinx.serialization.KSerializer serializer(...); }

# --- OkHttp ---------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Keep stack traces readable in exported diagnostics logs --------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
