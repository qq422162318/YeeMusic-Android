# ---------------------------------------------------------------------------
# YeeMusic Android - release build rules
#
# The single thing R8 must get right here is kotlinx.serialization: the whole
# data layer is built from @Serializable wire models that are decoded
# reflectively by name. Miss a keep rule and the app runs until the first
# network call and then throws "Serializer has not been found".
# ---------------------------------------------------------------------------

# --- kotlinx.serialization -------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

-keep,includedescriptorclasses @kotlinx.serialization.Serializable class **
-keepclassmembers,allowshrinking,allowobfuscation @kotlinx.serialization.Serializable {
    <fields>;
}

# Serializers are generated with the same visibility as the model.
-keep,includedescriptorclasses class **$$serializer { *; }

# Polymorphism is off, but generic envelopes like RespData<List<Song>> resolve
# their type arguments at call site.
-keep,includedescriptorclasses class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# --- OkHttp / Okio --------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicannotations.** { *; }
-keepclasseswithmembers class okhttp3.** {
    @okhttp3.CodeGeneratorMethod <methods>;
}

# --- Media3 (ExoPlayer) ---------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Coil -----------------------------------------------------------------
-dontwarn coil.**

# --- DataStore ------------------------------------------------------------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# --- Kotlin coroutines ----------------------------------------------------
-keep class kotlinx.coroutines.BuildKonan { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin metadata must survive for the Compose compiler and reflection.
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# Keep the generated BuildConfig so Settings can show the default node.
-keep class com.yeemusic.android.BuildConfig { *; }

# --- debug ----------------------------------------------------------------
# Keep class/method names readable in logcat; does not affect the APK size
# once minification is on, R8 still shrinks unused code.
-repackageclasses ''
