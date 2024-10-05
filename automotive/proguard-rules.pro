# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
# kotlinx.serialization
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses

# do not obfuscate, project is open source anyway
-dontobfuscate
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# kotlinx.serialization
-dontnote kotlinx.serialization.AnnotationsKt

# Setup ProGuard for use with kotlinx.serialization and JSON
# https://github.com/Kotlin/kotlinx.serialization/issues/1129

# Kotlin serialization looks up the generated serializer classes through a function on companion
# objects. The companions are looked up reflectively so we need to explicitly keep these functions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# If a companion has the serializer function, keep the companion field on the original type so that
# the reflective lookup succeeds.
-if class **.*$Companion {
  kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1>.<2> {
  <1>.<2>$Companion Companion;
}

# Strip most Android logcat logging from release builds which I have no access to anyway to reduce stress on logd
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
