# LinuxDroid ProGuard rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# JNI - keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep NativeBridge intact
-keep class com.linuxdroid.native_bridge.NativeBridge { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Hilt
-keep class dagger.hilt.** { *; }

# Serialization
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }

# LinuxDroid domain models
-keep class com.linuxdroid.core.model.** { *; }
