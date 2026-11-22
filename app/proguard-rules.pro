# SimFone MDM R8 rules

# Jackson-mapped server models (reflection on fields/getters/setters)
-keep class com.simfone.mdm.json.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses, Exceptions
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-keep class com.fasterxml.jackson.annotation.** { *; }

# Retrofit service interfaces
-keep interface com.simfone.mdm.server.ServerService { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# MQTT (Paho loads its network modules and logger by class name)
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.android.service.** { *; }
-dontwarn org.eclipse.paho.**

# AIDL companion API (hmdm-lib clients bind by name)
-keep class com.hmdm.** { *; }

# Workers are instantiated by class name
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
