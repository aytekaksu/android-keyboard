-keepattributes SourceFile,LineNumberTable

-keepclassmembers class * {
    @org.futo.inputmethod.annotations.ExternallyReferenced *;
}

# JNI registration uses these exact class names and method signatures.
-keep class org.futo.inputmethod.keyboard.ProximityInfo { *; }
-keep class org.futo.inputmethod.latin.AssetFileAddress { *; }
-keep class org.futo.inputmethod.latin.BinaryDictionary { *; }
-keep class org.futo.inputmethod.latin.utils.BinaryDictionaryUtils { *; }
-keep class org.futo.inputmethod.latin.DicTraverseSession { *; }
-keep class org.futo.inputmethod.latin.Dictionary { *; }
-keep class org.futo.inputmethod.latin.NgramContext { *; }
-keep class org.futo.inputmethod.latin.makedict.ProbabilityInfo { *; }
-keep class org.futo.inputmethod.latin.xlm.LanguageModel {
    native <methods>;
}
-keep class org.futo.inputmethod.latin.xlm.ModelInfo {
    public <init>(...);
}
-keep class org.futo.inputmethod.latin.xlm.ModelInfoLoader {
    native <methods>;
}
-keep class org.futo.ml.inference.** { *; }

-dontwarn javax.annotation.**
-dontwarn com.google.android.libraries.inputmethod.**
-dontwarn com.google.protobuf.**
-dontwarn com.osfans.trime.**
-dontwarn org.futo.voiceinput.**
-dontwarn org.mozc.**
