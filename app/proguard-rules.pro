# PKOS release shrinking rules (M8).
# Libraries with their own consumer rules (Room, WorkManager, kotlinx.serialization, OkHttp, ML Kit, Play services,
# Compose, Navigation) are not repeated here. Only code that is reached by reflection, JNI or resource paths is kept.

# Readable crash stack traces (mapping.txt is kept with each release build, never shipped in the APK).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Tesseract (tess-two) and Leptonica: native code reads/writes Java fields and calls Java methods by name. ---
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# --- pdfbox-android: loads fonts, glyph lists and CMaps by package-relative resource paths. ---
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.bind.**

# BouncyCastle (pulled in by pdfbox for encrypted PDFs) registers algorithms by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- zip4j (encrypted export) ---
-dontwarn net.lingala.zip4j.**

# --- PKOS enums are stored by name in Room and DataStore; keep their names stable. ---
-keepclassmembers enum com.pkos.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager instantiates PKOS workers by class name (also covered by WorkManager's rules; kept explicitly for safety).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
