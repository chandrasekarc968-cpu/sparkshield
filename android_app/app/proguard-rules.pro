# SparkShield ProGuard / R8 Rules
-keep class com.sparkshield.android.protocol.** { *; }
-keep class com.sparkshield.android.features.** { *; }
-keep class com.sparkshield.android.inference.** { *; }
-keep class com.sparkshield.android.data.** { *; }
-keep class ai.onnxruntime.** { *; }

# Keep native JNI methods and classes
-keepclasseswithmembernames class * {
    native <methods>;
}

