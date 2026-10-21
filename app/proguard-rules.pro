# t24：release 开启 R8 + 资源裁剪（isMinifyEnabled / isShrinkResources）。
# 项目用 org.json 手写解析，没有反射式序列化，所以这里的 keep 只覆盖
# 「运行时按名字查找 / ServiceLoader / 接口装配」的第三方入口。

# ---------- 基础 ----------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- Kotlin / 协程 ----------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------- Media3 / ExoPlayer ----------
# UnstableApi 是编译期注解（CLASS 保留），显式保留避免运行期断言失败
-keep @interface androidx.media3.common.util.UnstableApi
-keep class androidx.media3.common.util.UnstableApi { *; }
# 播放器组件（Renderer / Extractor / AudioSink）由 DefaultRenderersFactory 装配
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.audio.** { *; }
-keep class androidx.media3.exoplayer.video.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.datasource.** { *; }
-dontwarn androidx.media3.**

# ---------- Coil（含 okhttp 集成） ----------
# Coil 2.7 的 OkHttp 集成入口是 coil.fetch.HttpUriFetcher（无 OkHttpFetcher 类），
# 由 ImageLoader 直接引用；这里只保留对外工厂与请求模型，避免被 R8 内联后名字丢失。
-keep class coil.fetch.HttpUriFetcher { *; }
-keep class coil.ImageLoader { *; }
-keep class coil.ImageLoaderFactory { *; }
-keep class * implements coil.ImageLoaderFactory { *; }
-keep class coil.request.ImageRequest { *; }
-dontwarn coil.**

# ---------- zxing-core（局域网导入扫码） ----------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------- OkHttp / Okio 的可选 TLS 实现 ----------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**

# ---------- ProfileInstaller（baseline profile 安装器） ----------
-keep class androidx.profileinstaller.** { *; }
-dontwarn androidx.profileinstaller.**

# ---------- Compose ----------
-dontwarn androidx.compose.**
