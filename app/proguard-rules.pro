# Watchface ID Tool - R8 Rules (Java 21 / Kotlin 2.1)
# 深度优化版：
#   - 删除了原三条巨型 keep（androidx.compose.ui / 本项目 ui / kotlinx.coroutines 全量保留），
#     Compose 与协程库自带 consumer rules，无需手动 keep；全量 keep 会让 R8 裁剪/混淆收益归零。
#   - 仅保留 JNI、反射、sealed 类型匹配等真正需要的规则。

# ---- 保护微验 SDK（libwyverify.so JNI 静态符号注册，类名/方法名不可混淆） ----
# native 方法符号为 Java_com_watchface_idtool_weiyan_WYVerify_native*，R8 混淆会
# 导致 UnsatisfiedLinkError；结果类字段由 JNI 反射 Set*Field 写入，同样必须保留。
-keep class com.watchface.idtool.weiyan.** { *; }
-dontwarn com.watchface.idtool.weiyan.**

# ---- T3 SDK（JNI 反射写入结果类字段，保留结构） ----
-keep class com.watchface.idtool.oplusdk.SagVerify {
    public <fields>;
    public <methods>;
}
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3Result { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3LoginResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3NoticeResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3VersionResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3UpdateResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3QueryResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3VariableResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3CloudDocResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3CoreResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3OnlineResult { *; }
-keepclassmembers class com.watchface.idtool.oplusdk.SagVerify$T3AppSignResult { *; }
-dontwarn com.watchface.idtool.oplusdk.SagVerify

# ---- Shizuku（ShizukuExecutor 通过反射调用 Shizuku.newProcess，保留入口） ----
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---- Kotlin 元数据与协程调试信息（协程库自带 consumer rules，仅补齐属性） ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-dontwarn kotlinx.coroutines.**

# ---- sealed class / data class 的 instanceof 与 copy 结构（Kotlin 编译器已保证，
#      无需手动 keep；仅保留被字符串路由/序列化间接引用的核心模型） ----
-keep class com.watchface.idtool.CloudConfigManager$CloudConfig { *; }

# ---- R8 通用优化 ----
-allowaccessmodification
-useuniqueclassmembernames

# 移除调试日志（Release 不输出 Log.d/i/w/v，减小体积并避免信息泄漏）
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int v(...);
}

-dontnote org.json.**
-dontwarn org.json.**
-dontwarn com.google.protobuf.**
