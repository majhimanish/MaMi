# The Rust core is called through JNA, which uses reflection on the generated bindings.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class app.mami.core.** { *; }
-dontwarn java.awt.**

# WebRTC calls into Java from native code by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
