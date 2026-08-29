# Payloads crossing the Data Layer are serialized by name, so their fields have to survive R8.
-keepclassmembers class com.weighttrack.core.sync.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.weighttrack.core.sync.** {
    kotlinx.serialization.KSerializer serializer(...);
}
