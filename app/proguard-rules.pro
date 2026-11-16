# Keep line numbers & source file names for crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin & Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.example.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class com.example.data.local.** { *; }
-keepclassmembers class com.example.data.local.** { *; }

# LiteRT & LiteRT-LM Native JNI
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.tflite.** { *; }

# ML Kit On-Device Text Recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# App Services, Workers, Receivers & AccessibilityService
-keep public class com.example.automation.** { *; }
-keep public class com.example.voice.** { *; }

# OkHttp / Coroutines clean warnings suppression
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
