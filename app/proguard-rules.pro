# 02 · :app R8 规则
# Hilt / Room / Compose 自带 consumer 规则,无需手动 keep。
# kotlinx-serialization(导航类型安全路由使用):
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.me.jiakao.**$$serializer { *; }
-keepclassmembers class com.me.jiakao.** {
    *** Companion;
}
-keepclasseswithmembers class com.me.jiakao.** {
    kotlinx.serialization.KSerializer serializer(...);
}
