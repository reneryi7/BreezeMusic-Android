# Proguard rules for Kugou Concept Music App
-keep class com.concept.music.data.model.** { *; }
-keep class com.concept.music.data.recommendation.** { *; }
-keep class com.concept.music.data.repository.LocalListen* { *; }
-keep class com.concept.music.data.repository.PersistedLocalMusicIndex { *; }
-keep class com.concept.music.data.repository.LocalOnlyPlaylistRecord { *; }
-keep class com.concept.music.data.repository.PersistedLocalPlaylistStore { *; }
-keep class com.concept.music.service.CarBluetoothDeviceRecord { *; }
-keep enum com.concept.music.service.CarDeviceClassification { *; }
-keep class com.concept.music.service.PreCarEverydaySnapshot { *; }
-keep enum com.concept.music.service.CarCabinAcousticMode { *; }
-keep enum com.concept.music.service.CarInterludeStyle { *; }
-keep enum com.concept.music.service.CarConnectionSource { *; }
-keep class com.concept.music.data.kugou.SavedAccount { *; }
-keep class com.concept.music.data.kugou.KugouSession { *; }
-keep class com.concept.music.data.kugou.DeviceIdentity { *; }
-keep class com.concept.music.ui.viewmodel.SavedPlayQueue { *; }
-keep class androidx.media3.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Strip debug/verbose/info logs in release builds to prevent leaking user IDs, tokens, or playback traces
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
