# Baseline ProGuard / R8 rules for Simple Audio Stream

# Keep native methods if any are added in the future
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enum values and valueOf
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
