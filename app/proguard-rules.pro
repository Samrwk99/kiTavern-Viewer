# Public JavaScript bridge entrypoints are reached by name from webpage JS.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
