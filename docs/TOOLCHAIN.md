# 构建环境（实测可用，Captain 已验证）

| 项 | 路径/值 |
| --- | --- |
| JDK | `D:\tvbuild\jdk-17.0.2`（OpenJDK 17.0.2，`java -version` 通过） |
| Gradle | `D:\tvbuild\gradle-8.7`（`gradle --version` 通过） |
| Android SDK | `D:\tvbuild\android-sdk`（platforms\android-34、build-tools\34.0.0、platform-tools 已装，licenses 全部接受） |

## 构建前置（PowerShell）

```powershell
$env:JAVA_HOME='D:\tvbuild\jdk-17.0.2'
$env:ANDROID_HOME='D:\tvbuild\android-sdk'
$env:ANDROID_SDK_ROOT='D:\tvbuild\android-sdk'
```

工程的 `local.properties` 必须写：

```properties
sdk.dir=D:\\tvbuild\\android-sdk
```

构建命令（在工程根目录）：

```powershell
D:\tvbuild\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon -x lint
```

## 依赖仓库（本机直连 Maven Central 不稳定，必须加镜像）

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google(); mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google(); mavenCentral()
    }
}
```

## 版本基线（已确认互相兼容，禁止随手升级）

- AGP `8.5.2`，Kotlin `2.0.21`（`org.jetbrains.kotlin.plugin.compose`），Gradle 8.7
- compileSdk 34 / targetSdk 34 / minSdk 21
- Compose BOM `2024.09.03`，仅引 `compose-foundation` + `compose-ui`（**不引 Material3 / tv-material / Leanback / navigation-compose**）
- Media3 `1.5.1`（`media3-exoplayer` / `media3-exoplayer-hls` / `media3-ui` / `media3-datasource-okhttp`）
- OkHttp `4.12.0`，**Coil `2.7.0`（冻结，禁止引入 Glide）**

## 已冻结的技术决策

| 决策 | 结论 |
| --- | --- |
| 图片库 | Coil 2.7.0 单例 ImageLoader，与 API 共用 OkHttp client；memoryCache ≤24MB，diskCache ≤128MB，`allowHardware/allowRgb565`，列表固定 320×450 请求尺寸 |
| 源优先级 | `priority` 数值**小**优先（bfzy1=10 / 备用=20 / 直播=30） |
| 点播主端点 | `https://macapi1.com/maccms/json/baofeng/` —— **必须带结尾斜杠**，否则 307 且降级到 http |
| 源配置 | 严格实现 `tvapp.source.v1`（见 `assets/sources.schema.json`） |

## 已验证的数据接口（不要重复试错）

- 点播：`https://macapi1.com/maccms/json/baofeng?ac=detail&pg=1`（及 `ac=list`，返回 `class` 分类树）
- 播放：`vod_play_from=bfzym3u8`，`vod_play_url` 形如 `第1集$https://fengbao13.com/video/xxx/index.m3u8#...`，实测 200 可直接播
- 请求需带 `User-Agent` 与 `Referer: https://bfzy1.tv/`，不要用 shell 里裸测的默认 UA
