# kotlinx.serialization: сериализаторы ищутся по имени класса.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit: интерфейсы API и generic-сигнатуры suspend-функций.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Centrifugo-клиент: protobuf-lite генерирует классы по рефлексии.
-keep class io.github.centrifugal.centrifuge.internal.protocol.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
