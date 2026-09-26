# kotlinx.serialization: keep generated serializers of the wire models so
# R8 in consuming apps (:context-app, :keyboard) cannot strip or rename them.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class dev.context.core.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.context.core.**$$serializer { *; }

# AIDL stubs are looked up by descriptor string across processes.
-keep class dev.context.IContextService { *; }
-keep class dev.context.IContextService$* { *; }
