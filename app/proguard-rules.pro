# Keep kotlinx.serialization @Serializable types and their generated companions.
-keep,includedescriptorclasses class com.textserv.**$$serializer { *; }
-keepclassmembers class com.textserv.** {
    *** Companion;
}
-keepclasseswithmembers class com.textserv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
