# sherpa-onnx 语音识别引擎
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    public *;
    native <methods>;
}

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }

# Kotlin 元数据
-keep class kotlin.Metadata { *; }
-keepclassmembers class **.kt {
    public *;
}
