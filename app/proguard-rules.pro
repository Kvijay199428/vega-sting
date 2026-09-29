# ============================================================
# VEGA STING ProGuard / R8 rules
#
# NOTE: release builds currently set isMinifyEnabled = false, so
# these rules are dormant today. They are kept correct and ready so
# that enabling minification later does not break Room, Gson or
# kotlinx.serialization reflection.
# ============================================================

# ---- Kotlin metadata ----
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---- Gson: DeviceProfile JSON serialization ----
# Generic signatures are required so Gson can resolve type tokens.
-keepattributes Signature
-keep class com.vega.sting.camera.DeviceProfile { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ---- Compose ----
-dontwarn androidx.compose.**

# ---- CameraX ----
-dontwarn androidx.camera.**
