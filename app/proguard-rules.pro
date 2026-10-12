# Onyx SDK — accessed via reflection and AIDL
-keep class com.onyx.android.sdk.** { *; }

# AndroidX Ink native loader
-keep class androidx.ink.** { *; }

# Suppress warnings for Onyx SDK transitive dependencies not present at runtime
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.joda.convert.**
