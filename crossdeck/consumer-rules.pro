# Consumer ProGuard rules — applied to apps that depend on the
# Crossdeck library. The SDK uses no reflection / native code,
# so the surface area for keep-rules is minimal.
#
# Keep the public API. R8/ProGuard can rename public surface if
# not kept, which would break call sites in apps that reflectively
# touch the SDK (Flutter / Cordova bridge, multi-language adapter).

-keep public class com.crossdeck.** { public *; }
-keepclassmembers class com.crossdeck.** { public *; }
