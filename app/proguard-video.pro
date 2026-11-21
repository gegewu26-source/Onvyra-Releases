# Commons Compress registers ZIP extra-field implementations with Class.newInstance().
# R8 cannot infer that reflective construction from the registry's Class literals.
# Keep concrete implementations and their public no-arg constructors in optimized APKs.
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.** implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
-keep interface org.apache.commons.compress.archivers.zip.ZipExtraField { *; }

# Rhino's optional JavaBean-to-JSON converter references this desktop-JVM class.
# Reader rules use safe JS NativeObject/NativeArray values, never Java wrappers
# or the BEAN converter; JVM access is denied by the rule runtime.
-dontwarn java.beans.BeanDescriptor

# Rhino loads its interpreter, VM bridge, RegExp, typed-array and XML builtins
# from literal class names. LazilyLoadedCtor also discovers static init methods
# by reflection. Imported sources can exercise different builtin paths, so keep
# this scripting dependency intact rather than fixing one removed class at a
# time. This does not disable optimization for Onvyra or other dependencies;
# the rule runtime's safe globals and denied JVM class access remain enforced.
# The desktop debugger/shell tools depend on Swing/AWT and are never reader globals.
-keep class !org.mozilla.javascript.tools.**,org.mozilla.javascript.** { *; }
-keep interface !org.mozilla.javascript.tools.**,org.mozilla.javascript.** { *; }

# Internal, network-free Android compatibility check. Instrumentation invokes
# this method through the installed app class loader so it also tests R8 output.
-keep class com.constx.onvyra.ui.pages.novel.ReaderRuntimeCompatibilityProbe {
    public static java.lang.String verify();
}

# Invoked only by the owner's separately signed, network-free QA instrumentation.
-keep class com.constx.onvyra.security.ReleaseRuntimeCompatibilityProbe {
    public static java.lang.String verify(android.content.Context);
    public static java.lang.String verifyGalleryExport(android.content.Context);
}
