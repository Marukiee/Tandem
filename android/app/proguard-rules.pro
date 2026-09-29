# UniFFI bindings talk to the Rust library through JNA, which looks members up by name.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.*
-dontwarn com.sun.jna.**

# Shizuku starts this by class name in its own process, through the empty constructor.
-keep class nl.markmaaktmedia.tandem.hotspot.HotspotUserService { <init>(); }
-keep class rikka.shizuku.** { *; }
