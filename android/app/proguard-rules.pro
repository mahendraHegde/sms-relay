# Paho loads its network modules and logger reflectively.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# Only the BC lightweight API is used; keep what R8 cannot see through.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
