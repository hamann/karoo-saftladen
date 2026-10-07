# kotlinx.serialization keeps the generated serializers on the classes it annotates.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    *** descriptor;
}

# The Karoo System binds this service by name from the manifest.
-keep class io.github.hamann.saftladen.extension.SaftladenExtension { *; }
-keep class io.hammerhead.karooext.** { *; }
