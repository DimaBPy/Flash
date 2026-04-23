# Keep NFC payload data classes
-keep class com.example.flash.nfc.** { *; }

# Ktor uses reflection for routing
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.example.flash.**$$serializer { *; }
-keepclassmembers class com.example.flash.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.flash.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose Preview
-keep class androidx.compose.ui.tooling.preview.** { *; }
