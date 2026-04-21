# Preserve file names and line numbers for readable stack traces
-keepattributes SourceFile,LineNumberTable

# Replace source file paths with just "SourceFile" to avoid leaking build paths
-renamesourcefileattribute SourceFile

# Disable obfuscation entirely
-dontobfuscate

-keepclasseswithmembernames class * {
    native <methods>;
}

# JNA: native code looks up methods/fields reflectively via initIDs
-dontwarn java.awt.*
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# uniffi-generated bindings: Structure subclasses, Callback interfaces, and
# enum/data classes crossing the FFI boundary must retain their fields.
-keep class uniffi.bindings.** { *; }
-keep class uniffi.translator.** { *; }
