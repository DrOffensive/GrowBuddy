# kotlinx.serialization: keep generated serializers for @Serializable DTOs.
-keepclassmembers @kotlinx.serialization.Serializable class com.growbuddy.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature
