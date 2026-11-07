# Fathom release rules.
#
# kotlinx.serialization keeps its generated serializers through the compiler
# plugin, but a release build still needs the models kept by name for the
# reflective fallback that reads a `$type` discriminator.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class uk.ewancroft.fathom.shared.model.** {
    *** Companion;
}
-keepclasseswithmembers class uk.ewancroft.fathom.shared.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp warns about optional platform integrations it probes reflectively.
-dontwarn okhttp3.**
-dontwarn okio.**
