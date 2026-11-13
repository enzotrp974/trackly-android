# Règles R8 de Trackly.
#
# OkHttp et kotlinx.serialization fournissent leurs propres règles (META-INF/proguard et
# META-INF/com.android.tools dans leurs jars) : elles s'appliquent automatiquement.
# On garde en plus, par prudence, les sérialiseurs générés des classes @Serializable de l'app
# (arguments d'outils de l'assistant, cartes du chat stockées en JSON), y compris leurs
# sous-classes scellées, lues par polymorphisme.
-keepclassmembers @kotlinx.serialization.Serializable class app.trackly.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.trackly.**$$serializer { *; }
-keepattributes *Annotation*, InnerClasses, Signature

# Appel : WebRTC appelle ses classes Java depuis le code natif (JNI) ; R8 ne doit ni les retirer ni les renommer.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Le pont JNI de la librairie (jni_zero) est chargé par le code natif ; sans cette règle, la release plante
# (ClassNotFoundException org.jni_zero.JniZero).
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
