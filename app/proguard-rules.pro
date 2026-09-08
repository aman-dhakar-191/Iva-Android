# The JavaScript bridge is reached only by name from JavaScript, so the shrinker
# cannot see those call sites. Keep every @JavascriptInterface member.
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
