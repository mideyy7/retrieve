# R8 keep rules for the release build. See docs/IMPLEMENTATION_PLAN.md, step R1.

# JNI: native code looks up these classes and methods by name, so R8 must not rename or remove them.
-keep class com.mideyy7.retrieve.nativebridge.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# PdfBox-Android: JPEG 2000 decoding is an optional dependency we don't ship. Pages with JPX
# images will lack that image's text layer, which the OCR fallback covers.
-dontwarn com.gemalto.jp2.JP2Decoder

# ML Kit: ComponentDiscovery creates its registrars by reflection through their no-arg
# constructors. R8 full mode removed those constructors, so OCR failed at startup in release
# builds (NoSuchMethodException ... Registrar.<init>) while debug builds worked.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
