# Правила для релизной сборки с R8.
#
# Без них сжатие ломает две вещи: сериализацию (kotlinx.serialization
# ищет сгенерированные сериализаторы отражением по имени класса) и Ktor
# с OkHttp, которые подтягивают часть классов только через ServiceLoader.

# ---- kotlinx.serialization ----
# Аннотация @Serializable порождает вложенный объект $$serializer; ищется
# он по имени, поэтому ни классы данных, ни сами сериализаторы переименовывать
# нельзя — иначе ответ сервера падает с SerializationException уже в бою.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.example.frolovsistems.**$$serializer { *; }
-keepclassmembers class com.example.frolovsistems.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.frolovsistems.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.example.frolovsistems.** { *; }

# ---- Ktor и OkHttp ----
# Движки и плагины Ktor подбираются через ServiceLoader, имена — в ресурсах.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Корутины ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Compose ----
# Компилятор Compose уже помечает нужное; глушим лишь предупреждения
# о необязательных классах, которых нет в рантайме Android.
-dontwarn androidx.compose.**

# Понятные трассы в отчётах о падениях: без этого номера строк теряются.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
