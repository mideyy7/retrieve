# R8 keep rules for the release build. See IMPLEMENTATION_PLAN.md, step R1.

# JNI: native code looks up these classes and methods by name, so R8 must not rename or remove them.
-keep class com.mideyy7.retrieve.nativebridge.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
