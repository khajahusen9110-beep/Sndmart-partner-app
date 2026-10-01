# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- Kept ready for when isMinifyEnabled is switched on (smoke-test the release first) ----
# Moshi models (codegen adapters + reflection fallback via KotlinJsonAdapterFactory)
-keep class com.example.data.model.** { *; }
-keepclassmembers class * { @com.squareup.moshi.Json <fields>; }
-keep class kotlin.Metadata { *; }
# Retrofit service interfaces and generic signatures
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation interface com.example.data.remote.SupabaseApiService
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
