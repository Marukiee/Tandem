# UniFFI bindings talk to the Rust library through JNA, which looks members up by name.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.*
-dontwarn com.sun.jna.**

# Shizuku starts this by class name in its own process, through the empty constructor.
-keep class nl.markmaaktmedia.tandem.hotspot.HotspotUserService { <init>(); }
-keep class rikka.shizuku.** { *; }

# The SSH of the terminal finds its ciphers and key types by their names.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# JSch finds Bouncy Castle by name, for Ed25519.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
