# Keep Room generated implementations reachable through reflection-free codegen.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps generated serializers referenced only from @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.weighttrack.** {
    *** Companion;
}
-keepclasseswithmembers class com.weighttrack.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Health Connect client uses reflection over record classes.
-keep class androidx.health.connect.client.records.** { *; }
-dontwarn androidx.health.connect.client.**

# Glance widgets are instantiated by the framework from the manifest.
-keep class com.weighttrack.widget.** { *; }
