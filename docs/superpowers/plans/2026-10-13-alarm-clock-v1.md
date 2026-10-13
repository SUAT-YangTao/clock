# 闹钟 App v1 核心版 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个可在 Android 12/13 真机日常使用的闹钟 app —— 支持按星期重复、锁屏全屏响铃、贪睡，且在熄屏 Doze、app 被划掉、重启手机后仍能精确响铃。

**Architecture:** 单模块 Compose app，分四层。`domain/` 是纯 Kotlin，包含全部时间计算逻辑，不 import 任何 `android.*`，因此能在 JVM 上毫秒级测试。`data/` 用 Room 存闹钟（**刻意不存"下次触发时间"**）。`alarm/` 把 domain 的计算结果翻译成 Android API 调用。`ui/` 是 Compose 界面。铃响链路为三级互为备份：全屏 Activity → 前台 Service → 通知。

**Tech Stack:** Kotlin 2.0.21 / Jetpack Compose (BOM 2024.11.00) / Room 2.6.1 + KSP / AGP 8.7.3 / Gradle 8.10.2 / JDK 17 / JUnit4 + Robolectric 4.14.1

**Spec:** `docs/superpowers/specs/2026-10-13-alarm-clock-design.md`

## Global Constraints

以下约束适用于本计划的**每一个**任务，不再逐条重复：

- `applicationId = "com.example.clock"`，`namespace = "com.example.clock"`
- `minSdk = 26`，`targetSdk = 35`，`compileSdk = 35`，Java/Kotlin target = 17
- **`domain/` 包内不得出现任何 `android.*` 或 `androidx.room.*` 的 import。** 这是架构的硬性约束，也是能在 JVM 上快速测试的前提。
- **`alarm/` 包不得 import `ui/` 包中任何类**（含 `MainActivity`、`RingActivity`）。需要打开 app 时用 `context.packageManager.getLaunchIntentForPackage()`。
- **上述分层约束有且仅有一个例外**：`AlarmReceiver` 直接 import `RingActivity`。理由与边界见 Task 13 Step 5。不要用反射或 action 匹配去"修正"它 —— 那只会引入新的失败点。
- `RepeatDays` 掩码位序：**bit0 = 周一**，bit1 = 周二，…，bit6 = 周日。对应 `java.time.DayOfWeek.value - 1`。
- **掩码 `0` 表示"仅响一次"**，全项目统一。
- **数据库中没有"下次触发时间"字段。** 下次触发时间永远由 `AlarmScheduler.scheduleAll()` 现算现注册。
- 所有 `PendingIntent` 必须带 `FLAG_IMMUTABLE`；需要更新时加 `FLAG_UPDATE_CURRENT`，取消时加 `FLAG_NO_CREATE`。
- `PendingIntent` 的 `requestCode` = `alarm.id.toInt()`（贪睡用 `SNOOZE_REQUEST_OFFSET + id`），且 `Intent` 必须设置唯一的 `data` URI。
- `AlarmScheduler.scheduleAll()` 有且仅有 6 个调用时机：开机、应用更新、改时间、改时区、闹钟增删改、闹钟响铃后。
- 界面文案全部用中文。
- 每个任务结束时必须跑测试并全绿再提交。两条命令都验证过可用，不要互相替换：
  - 全量：`./gradlew.bat test --no-daemon`（`test` 是 AGP 的聚合任务，跑 debug + release 两个变体）
  - 单个测试类：`./gradlew.bat :app:testDebugUnitTest --tests "<全限定类名>" --no-daemon`
  - **`./gradlew.bat test --tests "..."` 是不可用的**：聚合任务不认 `--tests`，会在编译前就报 `Unknown command-line option '--tests'`。本计划正文里凡是按类跑测试的地方，用的都是上面第二条。
- **两条已知的、无害的编译告警，见到不必追查：**
  - `ScheduledAlarm.triggerAtTime` 在本计划锁定的 Robolectric 4.14.1 里已标记 `@Deprecated`（替代品是 `triggerAtMs`）。Task 7/8 的测试代码读它，会各产生一条 `w:` 告警；它仍然可用，**不要去改**——换成 `triggerAtMs` 反而要先验证该字段在钉死版本里存在。
  - Kotlin 编译守护进程在 Windows 上偶发 `e: The daemon has terminated unexpectedly on startup attempt #1 with error code: 0.`，Gradle 会自动重启并继续，不是测试失败。

## 与 spec 的两处刻意偏差

spec 的 §2.2 文件清单里列了两个文件，本计划**不创建**它们。这是有意的，不是遗漏：

- **`ClockApp.kt`（Application 子类）** —— spec 用它做依赖装配的入口。本计划改为在 `AppGraph` 里用 `@Volatile` + `synchronized` 的懒加载单例达到同样效果，少一个类和一条 manifest 声明。代价是 `AppGraph` 需要额外提供 `resetForTesting()` 给 Robolectric 用（见 Task 6）。
- **`Converters.kt`（Room TypeConverter）** —— spec 设想把 `RepeatDays` 作为独立类型持久化。本计划把 `RepeatDays` 的 7 位掩码直接以 `Int` 存进实体，`RepeatDays` ↔ `Int` 的转换就是实体映射扩展函数里的一行。一个 `Int` 不需要 TypeConverter。

## Review Focus

以下是 spec 隐含、但**没有任何任务的自动化测试能覆盖**的失败模式，按最可能坑到使用者的顺序排列。每一条都必须在实现时人工确认，并在 Task 17 的真机回归清单（README.md）里有对应条目。

1. **用户在 Doze 休眠下静置数小时后闹钟不响。** 模拟器的 Doze 行为与真机不同，任何自动化测试都测不出来。这是闹钟 app 最致命也最难发现的故障。→ 只能真机验证（Task 17 第 8 条）。
2. **应用被用户从最近任务列表划掉后闹钟不响。** 若前台服务没起来、或 `AlarmManager` 注册被清理，闹钟会静默失效且用户毫不知情。→ 只能真机验证（Task 17 第 7 条）。
3. **手机重启后闹钟不响。** `SystemEventReceiver` 的 manifest 声明或 `android:exported` 写错时，`BOOT_COMPLETED` 收不到，且**没有任何报错**。→ Task 13 有 Robolectric 测试断言 receiver 触发了重算，Task 17 第 5 条真机复核。
4. **用户拒绝 `POST_NOTIFICATIONS` 权限（Android 13）。** 前台服务的常驻通知不可见，若全屏 Activity 也被抑制，就没有任何关闭入口，铃声会一直响到没电。→ Task 14 必须处理拒绝分支，Task 17 第 13 条验证。
5. **前台服务启动被系统拒绝时（issue 238340795，约 1/2500）出现双路铃声重叠或完全无声。** 降级路径的正确性依赖 `RingAudioController` 的幂等性。→ Task 8 用 fake player 测试幂等性，Task 17 第 11、12 条真机复核。
6. **一次性闹钟响铃后没有自动关闭，第二天同一时间又响。** → Task 13 有 Robolectric 测试，Task 17 第 3 条真机复核。
7. **手机处于静音/勿扰模式时闹钟不响。** 预期行为是**仍然响**（`USAGE_ALARM` 音频流在勿扰下按系统设置可能仍被抑制，需要确认实际表现并记录）。→ Task 17 第 14 条。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `settings.gradle.kts` | 仓库配置（含国内镜像），模块声明 |
| `gradle/libs.versions.toml` | 版本目录，所有依赖版本集中管理 |
| `app/build.gradle.kts` | app 模块构建配置、KSP、Compose |
| `app/src/main/AndroidManifest.xml` | 权限、Activity/Service/Receiver 声明 |
| `.../domain/RepeatDays.kt` | 重复星期值对象（7 位掩码）+ 中文摘要 |
| `.../domain/Alarm.kt` | 领域模型，纯数据类 |
| `.../domain/NextTrigger.kt` | **核心纯函数**：计算下次触发时刻、最近闹钟、剩余时间文案 |
| `.../data/AlarmEntity.kt` | Room 实体 + 与 `Alarm` 的双向映射扩展函数 |
| `.../data/AlarmDao.kt` | Room DAO |
| `.../data/AlarmDatabase.kt` | Room 数据库单例 |
| `.../data/AlarmRepository.kt` | 对外只暴露 `Alarm`，变更后回调触发重算 |
| `.../AppGraph.kt` | 手写依赖装配（不引入 Hilt） |
| `.../alarm/AlarmScheduler.kt` | **唯一编排入口**：重算并注册所有闹钟 |
| `.../alarm/RingAudioController.kt` | 铃声播放的单一持有者，幂等 start/stop |
| `.../alarm/AlarmPlayer.kt` | 播放器接口 + MediaPlayer 实现（为可测性抽出的接缝） |
| `.../alarm/RingService.kt` | 前台服务：常驻通知 + 提供通知栏关闭/贪睡入口 |
| `.../alarm/SnoozeManager.kt` | 贪睡临时闹钟 |
| `.../alarm/RingingAlarm.kt` | 响铃所需的展示数据 + Intent 打包/解包 |
| `.../alarm/AlarmReceiver.kt` | 到点被唤醒，串联全流程 |
| `.../alarm/SystemEventReceiver.kt` | 开机/更新/改时间/改时区 → 重算 |
| `.../ui/theme/Theme.kt` | Material 3 主题，动态取色 |
| `.../ui/MainActivity.kt` | 导航宿主 + 通知权限请求 |
| `.../ui/list/AlarmListScreen.kt` | 闹钟列表 |
| `.../ui/list/AlarmListViewModel.kt` | 列表状态 |
| `.../ui/edit/AlarmEditScreen.kt` | 新建/编辑 |
| `.../ui/edit/AlarmEditViewModel.kt` | 编辑草稿状态 |
| `.../ui/ring/RingActivity.kt` | 全屏响铃 Activity |
| `.../ui/ring/RingScreen.kt` | 响铃界面 |
| `README.md` | 真机回归清单 + 厂商 ROM 白名单说明 |

---

# 任务

### Task 1: 工具链安装与可构建的项目骨架

**Files:**
- Create: `.gitignore`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/example/clock/ui/MainActivity.kt`
- Create: `app/src/main/java/com/example/clock/ui/theme/Theme.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/resources/robolectric.properties`

**Interfaces:**
- Consumes: 无
- Produces: 一个能 `./gradlew assembleDebug` 和 `./gradlew test` 成功的空 Compose 项目；`com.example.clock.ui.theme.ClockTheme` composable 供后续所有界面使用。

- [ ] **Step 1: 安装 JDK 17**

```powershell
winget install --id Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements
```

若 `winget` 不可用，从 https://adoptium.net/temurin/releases/?version=17 下载 `.msi` 手动安装，勾选 "Set JAVA_HOME variable"。

验证（**需要新开一个终端**让环境变量生效）：

```powershell
java -version
```

预期：输出 `openjdk version "17.x.x"`。

- [ ] **Step 2: 安装 Android SDK 命令行工具**

```powershell
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
New-Item -ItemType Directory -Force -Path $sdkRoot
$url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$zip = "$env:TEMP\cmdline-tools.zip"
Invoke-WebRequest -Uri $url -OutFile $zip
Expand-Archive -Path $zip -DestinationPath "$env:TEMP\cmdline-tools-unzipped" -Force
New-Item -ItemType Directory -Force -Path "$sdkRoot\cmdline-tools"
Move-Item "$env:TEMP\cmdline-tools-unzipped\cmdline-tools" "$sdkRoot\cmdline-tools\latest"
```

若 `dl.google.com` 下载失败，改用清华镜像 `https://mirrors.tuna.tsinghua.edu.cn/AOSP/android/repository/commandlinetools-win-11076708_latest.zip`。

- [ ] **Step 3: 设置环境变量并接受 license**

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", (Split-Path (Split-Path (Get-Command java).Source)), "User")
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

`--licenses` 会逐条询问，全部输入 `y` 回车。

- [ ] **Step 4: 安装 SDK 组件**

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

- [ ] **Step 5: 写 `.gitignore`**

```gitignore
*.iml
.gradle/
local.properties
.idea/
.DS_Store
build/
captures/
.externalNativeBuild/
.cxx/
*.apk
*.aab

# 隔离工作树与 SDD 工作区（见 using-git-worktrees）
.worktrees/
.superpowers/
```

- [ ] **Step 6: 写 `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.11.00"
navigationCompose = "2.8.4"
room = "2.6.1"
coroutines = "1.9.0"
junit = "4.13.2"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

> 版本说明：这是 2024 年末一组经过验证互相兼容的版本，不是最新版。**不要**在实现过程中随手升级单个版本 —— AGP / Kotlin / KSP / Compose 编译器四者的版本必须成组匹配，单独升级任何一个都会导致构建失败。若确要升级，四者一起升。

- [ ] **Step 7: 写 `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        // 国内镜像优先；海外网络下这三个仓库解析失败会自动回落到下面的官方源
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "clock"
include(":app")
```

- [ ] **Step 8: 写 `build.gradle.kts`（根）**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 9: 写 `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 10: 写 `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
# 国内网络用腾讯云镜像；海外网络改回 https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=false
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 11: 写 `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.clock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.clock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 12: 写 `app/src/test/resources/robolectric.properties`**

```properties
sdk=33
```

> 固定为 33（Android 13，目标设备）而不是跟随 targetSdk 35，可以避免 Robolectric 为每个 SDK 版本下载额外的 android-all jar。

- [ ] **Step 13: 写 `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">闹钟</string>
</resources>
```

- [ ] **Step 14: 写 `app/src/main/java/com/example/clock/ui/theme/Theme.kt`**

```kotlin
package com.example.clock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ClockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Android 12+ 动态取色，正好匹配目标设备
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
```

- [ ] **Step 15: 写 `app/src/main/AndroidManifest.xml`（此刻只声明 MainActivity）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 16: 写 `app/src/main/java/com/example/clock/ui/MainActivity.kt`（临时占位）**

```kotlin
package com.example.clock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.clock.ui.theme.ClockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClockTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("闹钟")
                }
            }
        }
    }
}
```

- [ ] **Step 17: 生成 Gradle wrapper 并构建**

若 `gradlew.bat` 不存在（仓库是空的），先用已安装的 gradle 或从 https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip 解压出 gradle 后执行 `gradle wrapper --gradle-version 8.10.2`。

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`。

**若卡在下载超过 2 分钟**：说明镜像不通，把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成 `https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip`，或反过来。两个源至少有一个能通。

- [ ] **Step 18: 运行测试任务**

```powershell
.\gradlew.bat test --no-daemon
```

预期：`BUILD SUCCESSFUL`（此时还没有任何测试）。

- [ ] **Step 19: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle/ app/
git commit -m "build: 搭起可构建的 Compose 项目骨架（AGP 8.7.3 / Kotlin 2.0.21 / Room 2.6.1）"
```

---

### Task 2: RepeatDays 值对象

**Files:**
- Create: `app/src/main/java/com/example/clock/domain/RepeatDays.kt`
- Test: `app/src/test/java/com/example/clock/domain/RepeatDaysTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `RepeatDays(mask: Int)` —— `@JvmInline value class`，公开属性 `mask: Int`、`isOnceOnly: Boolean`
  - `fun includes(day: DayOfWeek): Boolean`
  - `fun toggle(day: DayOfWeek): RepeatDays`
  - `fun summary(): String`
  - `RepeatDays.Companion`: 常量 `NONE_MASK = 0`、`EVERY_DAY_MASK`、`WEEKDAYS_MASK`、`WEEKENDS_MASK`；值 `NONE`、`EVERY_DAY`、`WEEKDAYS`、`WEEKENDS`；函数 `bitOf(day)`、`of(vararg days)`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/domain/RepeatDaysTest.kt`

```kotlin
package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class RepeatDaysTest {

    @Test
    fun `bit0 是周一 bit6 是周日`() {
        assertEquals(0b0000001, RepeatDays.bitOf(DayOfWeek.MONDAY))
        assertEquals(0b0000010, RepeatDays.bitOf(DayOfWeek.TUESDAY))
        assertEquals(0b0000100, RepeatDays.bitOf(DayOfWeek.WEDNESDAY))
        assertEquals(0b0001000, RepeatDays.bitOf(DayOfWeek.THURSDAY))
        assertEquals(0b0010000, RepeatDays.bitOf(DayOfWeek.FRIDAY))
        assertEquals(0b0100000, RepeatDays.bitOf(DayOfWeek.SATURDAY))
        assertEquals(0b1000000, RepeatDays.bitOf(DayOfWeek.SUNDAY))
    }

    @Test
    fun `掩码 0 表示仅一次`() {
        assertTrue(RepeatDays.NONE.isOnceOnly)
        assertTrue(RepeatDays(0).isOnceOnly)
        assertFalse(RepeatDays.WEEKDAYS.isOnceOnly)
    }

    @Test
    fun `预设常量的掩码值正确`() {
        assertEquals(0x00, RepeatDays.NONE_MASK)
        assertEquals(0x7F, RepeatDays.EVERY_DAY_MASK)
        assertEquals(0x1F, RepeatDays.WEEKDAYS_MASK)
        assertEquals(0x60, RepeatDays.WEEKENDS_MASK)
    }

    @Test
    fun `includes 只对命中的星期为真`() {
        val monWedFri = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        assertTrue(monWedFri.includes(DayOfWeek.MONDAY))
        assertFalse(monWedFri.includes(DayOfWeek.TUESDAY))
        assertTrue(monWedFri.includes(DayOfWeek.WEDNESDAY))
        assertFalse(monWedFri.includes(DayOfWeek.THURSDAY))
        assertTrue(monWedFri.includes(DayOfWeek.FRIDAY))
        assertFalse(monWedFri.includes(DayOfWeek.SATURDAY))
        assertFalse(monWedFri.includes(DayOfWeek.SUNDAY))
    }

    @Test
    fun `WEEKDAYS 含且仅含周一至周五`() {
        val inWeek = DayOfWeek.entries.filter { RepeatDays.WEEKDAYS.includes(it) }
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            inWeek,
        )
    }

    @Test
    fun `WEEKENDS 含且仅含周六周日`() {
        val inWeekend = DayOfWeek.entries.filter { RepeatDays.WEEKENDS.includes(it) }
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), inWeekend)
    }

    @Test
    fun `EVERY_DAY 含全部七天`() {
        assertEquals(7, DayOfWeek.entries.count { RepeatDays.EVERY_DAY.includes(it) })
    }

    @Test
    fun `toggle 加入或移出某天`() {
        val monday = RepeatDays.of(DayOfWeek.MONDAY)
        assertEquals(monday.toggle(DayOfWeek.TUESDAY), RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY))
        assertEquals(RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY).toggle(DayOfWeek.TUESDAY), monday)
        // toggle 两次回到原状
        assertEquals(monday, monday.toggle(DayOfWeek.SUNDAY).toggle(DayOfWeek.SUNDAY))
    }

    @Test
    fun `summary 覆盖全部特例分支`() {
        assertEquals("仅一次", RepeatDays.NONE.summary())
        assertEquals("每天", RepeatDays.EVERY_DAY.summary())
        assertEquals("周一至周五", RepeatDays.WEEKDAYS.summary())
        assertEquals("周末", RepeatDays.WEEKENDS.summary())
    }

    @Test
    fun `summary 的默认分支按星期顺序拼接`() {
        assertEquals(
            "周一、周三、周五",
            RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).summary(),
        )
        // 顺序必须由 DayOfWeek 的自然顺序决定，与传入顺序无关
        assertEquals(
            "周一、周三、周五",
            RepeatDays.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY).summary(),
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.RepeatDaysTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'RepeatDays'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/domain/RepeatDays.kt`

```kotlin
package com.example.clock.domain

import java.time.DayOfWeek

/**
 * 闹钟的重复星期设置。
 *
 * 用 7 位掩码表示：bit0 = 周一，bit1 = 周二，…，bit6 = 周日，
 * 对应 [DayOfWeek.get].value - 1。
 *
 * 掩码为 0 表示"仅响一次"——这是全项目的统一约定，见 [isOnceOnly]。
 */
@JvmInline
value class RepeatDays(val mask: Int) {

    /** 掩码为 0，即不重复，只响一次。 */
    val isOnceOnly: Boolean get() = mask == NONE_MASK

    /** 该星期是否命中本设置。 */
    fun includes(day: DayOfWeek): Boolean = (mask and bitOf(day)) != 0

    /** 把某一天加入（原本没有）或移出（原本有）。 */
    fun toggle(day: DayOfWeek): RepeatDays = RepeatDays(mask xor bitOf(day))

    /** 用于列表页展示的中文摘要。 */
    fun summary(): String = when (mask) {
        NONE_MASK -> "仅一次"
        EVERY_DAY_MASK -> "每天"
        WEEKDAYS_MASK -> "周一至周五"
        WEEKENDS_MASK -> "周末"
        else -> DayOfWeek.entries
            .filter { includes(it) }
            .joinToString("、") { shortName(it) }
    }

    companion object {
        const val NONE_MASK = 0x00
        const val EVERY_DAY_MASK = 0x7F      // 0b111_1111
        const val WEEKDAYS_MASK = 0x1F       // 0b001_1111 周一~周五
        const val WEEKENDS_MASK = 0x60       // 0b110_0000 周六、周日

        val NONE = RepeatDays(NONE_MASK)
        val EVERY_DAY = RepeatDays(EVERY_DAY_MASK)
        val WEEKDAYS = RepeatDays(WEEKDAYS_MASK)
        val WEEKENDS = RepeatDays(WEEKENDS_MASK)

        /** 某一天对应的单个 bit。 */
        fun bitOf(day: DayOfWeek): Int = 1 shl (day.value - 1)

        /** 由若干天构造。 */
        fun of(vararg days: DayOfWeek): RepeatDays =
            RepeatDays(days.fold(0) { acc, day -> acc or bitOf(day) })
    }
}

private fun shortName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.RepeatDaysTest" --no-daemon
```

预期：全部 10 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/domain/RepeatDays.kt app/src/test/java/com/example/clock/domain/RepeatDaysTest.kt
git commit -m "feat(domain): 新增 RepeatDays 值对象与中文摘要"
```

---

### Task 3: NextTrigger.next() 核心纯函数

**Files:**
- Create: `app/src/main/java/com/example/clock/domain/NextTrigger.kt`
- Test: `app/src/test/java/com/example/clock/domain/NextTriggerTest.kt`

**Interfaces:**
- Consumes: `RepeatDays`（Task 2）的 `mask` 与 `NONE_MASK`；`RepeatDays.includes(DayOfWeek)`
- Produces: `NextTrigger.next(hour: Int, minute: Int, mask: Int, now: ZonedDateTime): ZonedDateTime`

- [ ] **Step 1: 写失败的测试**

覆盖 spec 第 4.2 节表格的全部 12 个场景，外加 6 个加固用例（毫秒越界、结果严格晚于 now、秒纳秒归零、跨年、闰年 2 月 29 日、非法参数）——**共 18 个**，以下方 Step 1 的测试代码和 Step 4 的「全部 18 个测试 PASS」为准。全部使用 `Asia/Shanghai` 时区。

`app/src/test/java/com/example/clock/domain/NextTriggerTest.kt`

```kotlin
package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NextTriggerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, second, nano, zone)

    /** 2026-10-12 是周一，用它作为基准日，星期几一目了然。 */
    private fun monday(hour: Int, minute: Int, second: Int = 0, nano: Int = 0) =
        at(2026, 10, 12, hour, minute, second, nano)

    private val monWedFri = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).mask
    private val once = RepeatDays.NONE_MASK
    private val everyDay = RepeatDays.EVERY_DAY_MASK

    @Test
    fun `场景1 今天未过点则返回今天`() {
        val result = NextTrigger.next(7, 0, once, monday(6, 59))
        assertEquals(monday(7, 0), result)
    }

    @Test
    fun `场景2 一次性闹钟今天已过点则返回明天`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 1))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景3 重复闹钟今天已过点则跳到下一个命中的星期`() {
        val result = NextTrigger.next(7, 0, monWedFri, monday(7, 1))
        assertEquals(at(2026, 10, 14, 7, 0), result)   // 周一 → 周三
    }

    @Test
    fun `场景4 恰好等于目标时刻时必须返回下一个周期而不是当前时刻`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 0, second = 0, nano = 0))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景5 周二触发时跳到周三`() {
        val result = NextTrigger.next(7, 0, monWedFri, at(2026, 10, 13, 8, 0))
        assertEquals(at(2026, 10, 14, 7, 0), result)
    }

    @Test
    fun `场景6 每天重复时 23-59 返回明天 07-00`() {
        val result = NextTrigger.next(7, 0, everyDay, monday(23, 59))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `场景7 仅周日时从周日 08-00 跳到下周日`() {
        val sundayOnly = RepeatDays.of(DayOfWeek.SUNDAY).mask
        val sundayMorning = at(2026, 10, 18, 8, 0)          // 2026-10-18 是周日
        assertEquals(DayOfWeek.SUNDAY, sundayMorning.dayOfWeek)

        val result = NextTrigger.next(7, 0, sundayOnly, sundayMorning)
        assertEquals(at(2026, 10, 25, 7, 0), result)         // 正好 7 天后
    }

    @Test
    fun `场景8 一次性闹钟跨月`() {
        val jan31Night = at(2026, 1, 31, 23, 0)
        val result = NextTrigger.next(0, 30, once, jan31Night)
        assertEquals(at(2026, 2, 1, 0, 30), result)
    }

    @Test
    fun `场景9 周末重复时从周五跳到周六`() {
        val friday = at(2026, 10, 16, 12, 0)                 // 2026-10-16 是周五
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)

        val result = NextTrigger.next(7, 0, RepeatDays.WEEKENDS_MASK, friday)
        assertEquals(at(2026, 10, 17, 7, 0), result)
    }

    @Test
    fun `场景10 仅周一且当天未过点时返回今天`() {
        val mondayOnly = RepeatDays.of(DayOfWeek.MONDAY).mask
        val result = NextTrigger.next(7, 0, mondayOnly, monday(6, 0))
        assertEquals(monday(7, 0), result)
    }

    @Test
    fun `场景11 23-59 的闹钟在 23-59-30 时返回明天`() {
        val result = NextTrigger.next(23, 59, once, monday(23, 59, second = 30))
        assertEquals(at(2026, 10, 13, 23, 59), result)
    }

    @Test
    fun `场景12 00-00 的闹钟在 00-00-00-000 时返回明天`() {
        val result = NextTrigger.next(0, 0, once, at(2026, 10, 12, 0, 0, 0, 0))
        assertEquals(at(2026, 10, 13, 0, 0), result)
    }

    @Test
    fun `毫秒之差也算已过点`() {
        val result = NextTrigger.next(7, 0, once, monday(7, 0, nano = 1))
        assertEquals(at(2026, 10, 13, 7, 0), result)
    }

    @Test
    fun `结果始终严格晚于 now`() {
        val samples = listOf(
            monday(0, 0), monday(6, 59), monday(7, 0), monday(7, 1), monday(23, 59, second = 59),
        )
        for (now in samples) {
            for (mask in listOf(once, everyDay, monWedFri, RepeatDays.WEEKENDS_MASK)) {
                val result = NextTrigger.next(7, 0, mask, now)
                assert(result.isAfter(now)) { "mask=$mask, now=$now, result=$result" }
            }
        }
    }

    @Test
    fun `结果落在目标时刻上且秒与纳秒归零`() {
        val result = NextTrigger.next(7, 0, monWedFri, monday(9, 30, second = 45, nano = 123))
        assertEquals(7, result.hour)
        assertEquals(0, result.minute)
        assertEquals(0, result.second)
        assertEquals(0, result.nano)
        assertEquals(zone, result.zone)
    }

    @Test
    fun `跨年边界`() {
        val newYearEve = at(2026, 12, 31, 23, 0)
        val result = NextTrigger.next(6, 30, once, newYearEve)
        assertEquals(at(2027, 1, 1, 6, 30), result)
    }

    @Test
    fun `闰年 2 月 29 日边界`() {
        val feb28 = at(2028, 2, 28, 23, 0)                   // 2028 是闰年
        val result = NextTrigger.next(6, 0, once, feb28)
        assertEquals(at(2028, 2, 29, 6, 0), result)
    }

    @Test
    fun `非法参数抛出异常`() {
        val now = monday(12, 0)
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(24, 0, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(-1, 0, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(7, 60, once, now) }
        assertThrows(IllegalArgumentException::class.java) { NextTrigger.next(7, -1, once, now) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.NextTriggerTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'NextTrigger'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/domain/NextTrigger.kt`

```kotlin
package com.example.clock.domain

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 计算闹钟的下次触发时刻。
 *
 * 这是整个 app 最容易出错、也最值得测试的一块，因此刻意做成不依赖任何
 * Android API 的纯函数，可以在 JVM 上直接测试。
 */
object NextTrigger {

    /** 重复闹钟最多向前搜索的天数。掩码非 0 时 7 天内必然命中至少一天。 */
    private const val MAX_SEARCH_DAYS = 7L

    /**
     * 返回严格晚于 [now] 的下一次触发时刻，时区取自 [now]。
     *
     * - 掩码为 0（仅一次）：今天的点还没到就是今天，已过点就是明天。
     * - 掩码非 0（重复）：只在命中的星期里找；今天已过点就跳到下一个命中的星期。
     * - **严格晚于**：若 [now] 恰好等于目标时刻（含秒与纳秒均为 0），返回下一个周期。
     *   违反这条会导致闹钟立刻重复触发，形成无限循环。
     *
     * @throws IllegalArgumentException 当 [hour] 不在 0..23 或 [minute] 不在 0..59。
     */
    fun next(hour: Int, minute: Int, mask: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23) { "hour 必须在 0..23，实际为 $hour" }
        require(minute in 0..59) { "minute 必须在 0..59，实际为 $minute" }

        val target = LocalTime.of(hour, minute)
        val repeatDays = RepeatDays(mask)
        val onceOnly = repeatDays.isOnceOnly

        // 一次性闹钟只需看今天和明天；重复闹钟看满 7 天。
        val searchLimit = if (onceOnly) 1L else MAX_SEARCH_DAYS

        for (offset in 0..searchLimit) {
            val day = now.toLocalDate().plusDays(offset)
            if (!onceOnly && !repeatDays.includes(day.dayOfWeek)) continue

            // 用 ZonedDateTime 而非 LocalDateTime：跨夏令时切换时，"明天 07:00"
            // 仍然是本地时间 07:00，而不是固定 24 小时之后。
            val candidate = ZonedDateTime.of(day, target, now.zone)
            if (candidate.isAfter(now)) return candidate
        }

        // 掩码非 0 时 7 天内必有解；走到这里说明前提被破坏了。
        error("无法在 ${MAX_SEARCH_DAYS + 1} 天内找到触发时刻：mask=$mask, now=$now")
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.NextTriggerTest" --no-daemon
```

预期：全部 18 个测试 PASS。

若 `场景7` 失败，检查 `2026-10-18` 是否真的是周日 —— 用一个临时测试打印 `LocalDate.of(2026,10,18).dayOfWeek` 确认，不要凭记忆改断言。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/domain/NextTrigger.kt app/src/test/java/com/example/clock/domain/NextTriggerTest.kt
git commit -m "feat(domain): 新增 NextTrigger.next() 纯函数，覆盖 12 个边界场景"
```

---

### Task 4: 列表页所需的派生逻辑（最近闹钟 + 剩余时间文案）

**Files:**
- Modify: `app/src/main/java/com/example/clock/domain/NextTrigger.kt`（追加两个函数）
- Create: `app/src/main/java/com/example/clock/domain/Alarm.kt`
- Test: `app/src/test/java/com/example/clock/domain/NextAlarmTest.kt`

**Interfaces:**
- Consumes: `NextTrigger.next`（Task 3）、`RepeatDays`（Task 2）
- Produces:
  - `data class Alarm(id: Long = 0L, hour: Int, minute: Int, label: String = "", repeatDays: RepeatDays = RepeatDays.NONE, enabled: Boolean = true, vibrate: Boolean = true)`
  - `NextTrigger.nextOf(alarms: List<Alarm>, now: ZonedDateTime): AlarmTrigger?`
  - `data class AlarmTrigger(val alarm: Alarm, val at: ZonedDateTime)`
  - `NextTrigger.formatRemaining(from: ZonedDateTime, to: ZonedDateTime): String`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/domain/NextAlarmTest.kt`

```kotlin
package com.example.clock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NextAlarmTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone)

    private fun alarm(
        id: Long,
        hour: Int,
        minute: Int,
        enabled: Boolean = true,
        mask: Int = RepeatDays.NONE_MASK,
    ) = Alarm(id = id, hour = hour, minute = minute, repeatDays = RepeatDays(mask), enabled = enabled)

    @Test
    fun `无闹钟时返回 null`() {
        assertNull(NextTrigger.nextOf(emptyList(), at(10, 12, 8, 0)))
    }

    @Test
    fun `全部禁用时返回 null`() {
        val alarms = listOf(alarm(1, 7, 0, enabled = false), alarm(2, 9, 0, enabled = false))
        assertNull(NextTrigger.nextOf(alarms, at(10, 12, 8, 0)))
    }

    @Test
    fun `返回时间最早的那个闹钟`() {
        val alarms = listOf(alarm(1, 9, 0), alarm(2, 7, 0), alarm(3, 8, 0))
        val result = NextTrigger.nextOf(alarms, at(10, 12, 6, 0))
        assertEquals(2L, result?.alarm?.id)
        assertEquals(at(10, 12, 7, 0), result?.at)
    }

    @Test
    fun `禁用中的闹钟不参与比较`() {
        val alarms = listOf(alarm(1, 7, 0, enabled = false), alarm(2, 9, 0))
        val result = NextTrigger.nextOf(alarms, at(10, 12, 6, 0))
        assertEquals(2L, result?.alarm?.id)
    }

    @Test
    fun `按绝对时刻比较而不是按小时数`() {
        val today = alarm(1, 23, 0, mask = RepeatDays.WEEKDAYS_MASK)
        val tomorrow = alarm(2, 1, 0, mask = RepeatDays.EVERY_DAY_MASK)
        // 周一 10:00
        val now = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, zone)
        assertEquals(DayOfWeek.MONDAY, now.dayOfWeek)

        val result = NextTrigger.nextOf(listOf(today, tomorrow), now)
        // today：周一 23:00（今天稍晚）；tomorrow：周二 01:00。今天 23:00 更早。
        assertEquals(1L, result?.alarm?.id)
        assertEquals(ZonedDateTime.of(2026, 10, 12, 23, 0, 0, 0, zone), result?.at)
    }

    @Test
    fun `剩余时间文案 超过一小时`() {
        assertEquals("7 小时 12 分钟后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 7, 12)))
    }

    @Test
    fun `剩余时间文案 恰好整小时`() {
        assertEquals("3 小时后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 3, 0)))
    }

    @Test
    fun `剩余时间文案 不足一小时`() {
        assertEquals("45 分钟后", NextTrigger.formatRemaining(at(10, 12, 0, 0), at(10, 12, 0, 45)))
    }

    @Test
    fun `剩余时间文案 不足一分钟`() {
        val from = ZonedDateTime.of(2026, 10, 12, 7, 0, 30, 0, zone)
        assertEquals("剩余零秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from))
        assertEquals("剩余 30 秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from.plusSeconds(30)))
        assertEquals("剩余 59 秒", "不到 1 分钟", NextTrigger.formatRemaining(from, from.plusSeconds(59)))
    }

    @Test
    fun `剩余时间文案 恰好一分钟`() {
        val from = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, zone)
        assertEquals("1 分钟后", NextTrigger.formatRemaining(from, from.plusMinutes(1)))
    }

    @Test
    fun `剩余时间文案 跨天`() {
        assertEquals("1 天 2 小时后", NextTrigger.formatRemaining(at(10, 12, 22, 0), at(10, 14, 0, 0)))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.NextAlarmTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'Alarm'`。

- [ ] **Step 3: 写 `Alarm.kt`**

`app/src/main/java/com/example/clock/domain/Alarm.kt`

```kotlin
package com.example.clock.domain

/**
 * 一个闹钟设置。
 *
 * 纯 Kotlin 数据类，不依赖任何 Android 或 Room 类型。
 *
 * [id] 为 0 表示尚未写入数据库的新闹钟。
 */
data class Alarm(
    val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatDays: RepeatDays = RepeatDays.NONE,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
)
```

- [ ] **Step 4: 在 `NextTrigger.kt` 追加两个函数**

在 `NextTrigger` object 内部、`next()` 之后追加：

```kotlin
    /** 一个闹钟及其下次触发时刻。 */
    data class AlarmTrigger(val alarm: Alarm, val at: ZonedDateTime)

    /**
     * 在所有**已启用**的闹钟里，找出下次触发最早的那个。没有则返回 null。
     * 供列表页顶部的"下一个闹钟"摘要使用。
     */
    fun nextOf(alarms: List<Alarm>, now: ZonedDateTime): AlarmTrigger? =
        alarms.asSequence()
            .filter { it.enabled }
            .map { AlarmTrigger(it, next(it.hour, it.minute, it.repeatDays.mask, now)) }
            .minByOrNull { it.at }

    /**
     * 把两个时刻之差格式化成中文文案，例如 "7 小时 12 分钟后"、"45 分钟后"。
     *
     * 规则：不足 1 分钟 → "不到 1 分钟"；不足 1 小时 → "N 分钟后"；
     * 不足 1 天 → "H 小时 M 分钟后" / "H 小时后"；否则 "D 天 H 小时后" / "D 天后"。
     */
    fun formatRemaining(from: ZonedDateTime, to: ZonedDateTime): String {
        val totalMinutes = java.time.Duration.between(from, to).toMinutes()
        if (totalMinutes < 1) return "不到 1 分钟"

        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        return when {
            days > 0 && hours > 0 -> "$days 天 $hours 小时后"
            days > 0 -> "$days 天后"
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟后"
            hours > 0 -> "$hours 小时后"
            else -> "$minutes 分钟后"
        }
    }
```

`Alarm.kt` 与 `NextTrigger.kt` 同包，无需 import。

- [ ] **Step 5: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.domain.NextAlarmTest" --no-daemon
```

预期：全部 PASS。

同时跑一次全量测试确认 Task 2、3 没有被破坏：

```powershell
.\gradlew.bat test --no-daemon
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/clock/domain/ app/src/test/java/com/example/clock/domain/
git commit -m "feat(domain): 新增 Alarm 模型、nextOf 与剩余时间文案"
```

---

### Task 5: Room 实体与映射

**Files:**
- Create: `app/src/main/java/com/example/clock/data/AlarmEntity.kt`
- Test: `app/src/test/java/com/example/clock/data/AlarmMappingTest.kt`

**Interfaces:**
- Consumes: `Alarm`、`RepeatDays`（Task 2、4）
- Produces:
  - `data class AlarmEntity(id: Long = 0L, hour: Int, minute: Int, label: String, repeatMask: Int, enabled: Boolean, vibrate: Boolean)`
  - `fun AlarmEntity.toDomain(): Alarm`
  - `fun Alarm.toEntity(): AlarmEntity`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/data/AlarmMappingTest.kt`

```kotlin
package com.example.clock.data

import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class AlarmMappingTest {

    @Test
    fun `Alarm 转 Entity 再转回完全一致`() {
        val original = Alarm(
            id = 42L,
            hour = 7,
            minute = 30,
            label = "起床",
            repeatDays = RepeatDays.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            enabled = false,
            vibrate = false,
        )
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `掩码被原样搬运`() {
        val alarm = Alarm(hour = 6, minute = 0, repeatDays = RepeatDays.WEEKENDS)
        assertEquals(RepeatDays.WEEKENDS_MASK, alarm.toEntity().repeatMask)
        assertEquals(RepeatDays.WEEKENDS_MASK, alarm.toEntity().toDomain().repeatDays.mask)
    }

    @Test
    fun `新闹钟 id 为 0 时保持为 0`() {
        assertEquals(0L, Alarm(hour = 6, minute = 0).toEntity().id)
    }

    @Test
    fun `仅一次的掩码 0 也能往返`() {
        val alarm = Alarm(hour = 6, minute = 0, repeatDays = RepeatDays.NONE)
        val roundTripped = alarm.toEntity().toDomain()
        assertEquals(0, roundTripped.repeatDays.mask)
        assertEquals(true, roundTripped.repeatDays.isOnceOnly)
    }

    @Test
    fun `Entity 转 Domain 字段一一对应`() {
        val entity = AlarmEntity(
            id = 7L, hour = 21, minute = 5, label = "吃药",
            repeatMask = RepeatDays.EVERY_DAY_MASK, enabled = true, vibrate = false,
        )
        val domain = entity.toDomain()
        assertEquals(7L, domain.id)
        assertEquals(21, domain.hour)
        assertEquals(5, domain.minute)
        assertEquals("吃药", domain.label)
        assertEquals(RepeatDays.EVERY_DAY, domain.repeatDays)
        assertEquals(true, domain.enabled)
        assertEquals(false, domain.vibrate)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.data.AlarmMappingTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'AlarmEntity'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/data/AlarmEntity.kt`

```kotlin
package com.example.clock.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays

/**
 * 闹钟的持久化形式。
 *
 * 注意这里**没有**"下次触发时间"字段——那是刻意的设计：任何持久化的触发时间
 * 都可能与实际状态不同步（用户改了系统时间、时区变了），一旦不同步就会出现
 * "闹钟静默不响"这种最难排查的故障。下次触发时间永远由 AlarmScheduler 现算。
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String,
    val repeatMask: Int,
    val enabled: Boolean,
    val vibrate: Boolean,
)

fun AlarmEntity.toDomain(): Alarm = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    repeatDays = RepeatDays(repeatMask),
    enabled = enabled,
    vibrate = vibrate,
)

fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    repeatMask = repeatDays.mask,
    enabled = enabled,
    vibrate = vibrate,
)
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.data.AlarmMappingTest" --no-daemon
```

预期：5 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/data/AlarmEntity.kt app/src/test/java/com/example/clock/data/AlarmMappingTest.kt
git commit -m "feat(data): 新增 AlarmEntity 与领域模型双向映射"
```

---

### Task 6: DAO、数据库、Repository 与依赖装配

**Files:**
- Create: `app/src/main/java/com/example/clock/data/AlarmDao.kt`
- Create: `app/src/main/java/com/example/clock/data/AlarmDatabase.kt`
- Create: `app/src/main/java/com/example/clock/data/AlarmRepository.kt`
- Create: `app/src/main/java/com/example/clock/AppGraph.kt`
- Test: `app/src/test/java/com/example/clock/data/AlarmRepositoryTest.kt`

**Interfaces:**
- Consumes: `AlarmEntity`、`toDomain()`、`toEntity()`（Task 5）
- Produces:
  - `interface AlarmDao` 方法：`observeAll(): Flow<List<AlarmEntity>>`、`getAll(): List<AlarmEntity>`（suspend）、`getEnabled(): List<AlarmEntity>`（suspend）、`getById(id: Long): AlarmEntity?`（suspend）、`insert(entity): Long`（suspend）、`update(entity)`（suspend）、`deleteById(id: Long)`（suspend）
  - `abstract class AlarmDatabase : RoomDatabase`，`AlarmDatabase.get(context): AlarmDatabase`，`fun alarmDao(): AlarmDao`
  - `class AlarmRepository(dao: AlarmDao, onAlarmsChanged: suspend (removedId: Long?) -> Unit)`，方法：`observeAll(): Flow<List<Alarm>>`、`getAll(): List<Alarm>`（suspend）、`getEnabled(): List<Alarm>`（suspend）、`getById(id): Alarm?`（suspend）、`save(alarm): Long`（suspend）、`delete(id)`（suspend）、`setEnabled(id, enabled)`（suspend）、`disableSilently(id)`（suspend）
  - `object AppGraph`：`fun repository(context: Context): AlarmRepository`、`fun scheduler(context: Context): AlarmScheduler`

> `AppGraph.scheduler` 在 Task 7 才会存在。本步先只写 `repository`，`scheduler` 留在 Task 7 追加。

- [ ] **Step 1: 写失败的测试**

用 Robolectric 提供真实的 in-memory Room 数据库，从而在 JVM 上验证 SQL 正确性，不需要真机。

`app/src/test/java/com/example/clock/data/AlarmRepositoryTest.kt`

```kotlin
package com.example.clock.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
class AlarmRepositoryTest {

    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private var changeCallbackCount = 0
    private val removedIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AlarmDatabase::class.java,
        ).allowMainThreadQueries().build()

        changeCallbackCount = 0
        removedIds.clear()
        repository = AlarmRepository(db.alarmDao()) { removedId ->
            changeCallbackCount++
            if (removedId != null) removedIds += removedId
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `新建闹钟后能查回来`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 30, label = "起床"))
        val loaded = repository.getById(id)
        assertEquals(7, loaded?.hour)
        assertEquals(30, loaded?.minute)
        assertEquals("起床", loaded?.label)
    }

    @Test
    fun `保存既有闹钟是更新而不是新增`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, label = "旧"))
        repository.save(Alarm(id = id, hour = 8, minute = 15, label = "新"))

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(8, all[0].hour)
        assertEquals("新", all[0].label)
    }

    @Test
    fun `删除后查不到`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.delete(id)
        assertNull(repository.getById(id))
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun `用已删除的 id 保存会重新插入而不是静默丢失`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, label = "起床"))
        repository.delete(id)

        // 列表页"撤销删除"走的就是这条路径
        val restored = repository.save(Alarm(id = id, hour = 7, minute = 0, label = "起床"))

        assertEquals(1, repository.getAll().size)
        assertEquals("起床", repository.getById(restored)?.label)
    }

    @Test
    fun `getEnabled 只返回已启用的闹钟`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.save(Alarm(hour = 8, minute = 0, enabled = false))
        repository.save(Alarm(hour = 9, minute = 0, enabled = true))

        val enabled = repository.getEnabled()
        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `getAll 包含已禁用的闹钟`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.save(Alarm(hour = 8, minute = 0, enabled = false))
        assertEquals(2, repository.getAll().size)
    }

    @Test
    fun `setEnabled 切换开关`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0, enabled = true))
        repository.setEnabled(id, false)
        assertEquals(false, repository.getById(id)?.enabled)
        repository.setEnabled(id, true)
        assertEquals(true, repository.getById(id)?.enabled)
    }

    @Test
    fun `掩码完整往返`() = runTest {
        val mask = RepeatDays.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY).mask
        val id = repository.save(Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(mask)))
        assertEquals(mask, repository.getById(id)?.repeatDays?.mask)
    }

    @Test
    fun `变更会触发重算回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        assertEquals(1, changeCallbackCount)

        repository.setEnabled(id, false)
        assertEquals(2, changeCallbackCount)

        repository.delete(id)
        assertEquals(3, changeCallbackCount)
    }

    @Test
    fun `删除时把被删的 id 交给回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.delete(id)

        assertEquals(
            "删除必须把 id 传出去，否则已注册的闹钟再也无法取消",
            listOf(id),
            removedIds,
        )
    }

    @Test
    fun `新增和修改时回调不带 removedId`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        repository.setEnabled(id, false)

        assertTrue("只有删除才应传出 id，实际 $removedIds", removedIds.isEmpty())
    }

    @Test
    fun `disableSilently 不触发重算回调`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        val before = changeCallbackCount

        repository.disableSilently(id)

        assertEquals(before, changeCallbackCount)
        assertEquals(false, repository.getById(id)?.enabled)
    }

    @Test
    fun `observeAll 按时间排序`() = runTest {
        repository.save(Alarm(hour = 9, minute = 0))
        repository.save(Alarm(hour = 6, minute = 30))
        repository.save(Alarm(hour = 7, minute = 0))

        val list = repository.observeAll().first()
        assertEquals(listOf(6 to 30, 7 to 0, 9 to 0), list.map { it.hour to it.minute })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.data.AlarmRepositoryTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'AlarmDatabase'`。

若报错 `Robolectric requires ... android-all` 或下载失败，检查 `app/src/test/resources/robolectric.properties` 是否存在且内容为 `sdk=33`。

- [ ] **Step 3: 写 DAO**

`app/src/main/java/com/example/clock/data/AlarmDao.kt`

```kotlin
package com.example.clock.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    /** 列表页订阅用。按时间排序，跨天比较交给界面层的 NextTrigger。 */
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAll(): Flow<List<AlarmEntity>>

    /** 调度器用：包含已禁用的，因为需要把陈旧的注册也一并清掉。 */
    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Insert
    suspend fun insert(entity: AlarmEntity): Long

    @Update
    suspend fun update(entity: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

- [ ] **Step 4: 写数据库**

`app/src/main/java/com/example/clock/data/AlarmDatabase.kt`

```kotlin
package com.example.clock.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AlarmEntity::class], version = 1, exportSchema = true)
abstract class AlarmDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile
        private var instance: AlarmDatabase? = null

        fun get(context: Context): AlarmDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlarmDatabase::class.java,
                    "clock.db",
                ).build().also { instance = it }
            }

        /**
         * 仅供测试：关闭并清空单例。
         *
         * Robolectric 每个测试都会新建 Application，但 object / companion object 里的
         * 静态状态**不会**随之重置。不在 @Before 里调用它，后一个测试就会拿到上一个
         * 测试遗留的、指向已失效目录的数据库实例。
         */
        @VisibleForTesting
        fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
```

需要 import：`import androidx.annotation.VisibleForTesting`。

- [ ] **Step 5: 写 Repository**

`app/src/main/java/com/example/clock/data/AlarmRepository.kt`

```kotlin
package com.example.clock.data

import com.example.clock.domain.Alarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 闹钟数据的唯一出入口。对外只暴露 [Alarm]，界面层与 alarm 层都看不到 [AlarmEntity]。
 *
 * [onAlarmsChanged] 是"闹钟变了，请重算注册"的钩子。用回调而不是直接依赖
 * AlarmScheduler，是为了保持依赖方向单一（data 不 import alarm），同时保证
 * 任何一处改动都不可能忘记触发重算。
 *
 * 回调带一个 [Long]? 参数：**删除时传入被删的 id，其余情况传 null**。
 * 这个参数不是可选的便利，而是正确性所必需的 —— 见 [delete] 的说明。
 */
class AlarmRepository(
    private val dao: AlarmDao,
    private val onAlarmsChanged: suspend (removedId: Long?) -> Unit,
) {

    fun observeAll(): Flow<List<Alarm>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getAll(): List<Alarm> = dao.getAll().map { it.toDomain() }

    suspend fun getEnabled(): List<Alarm> = dao.getEnabled().map { it.toDomain() }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)?.toDomain()

    /**
     * 新增或更新（upsert）。返回闹钟 id。
     *
     * id 非 0 但数据库里查不到时走 insert 而不是 update：`@Update` 匹配不到行时
     * 会**静默影响 0 行**，闹钟就此凭空消失且没有任何报错。列表页的"撤销删除"
     * 正依赖这条语义。
     */
    suspend fun save(alarm: Alarm): Long {
        val id = when {
            alarm.id == 0L -> dao.insert(alarm.toEntity())
            dao.getById(alarm.id) == null -> dao.insert(alarm.toEntity())
            else -> {
                dao.update(alarm.toEntity())
                alarm.id
            }
        }
        onAlarmsChanged(null)
        return id
    }

    /**
     * 删除闹钟。
     *
     * **必须把 id 交给回调。** `AlarmScheduler.scheduleAll()` 是靠遍历数据库里的
     * 闹钟来取消注册的；一旦行被删掉，调度器就再也拼不出这个闹钟的 PendingIntent，
     * 那个已经注册的闹钟**再也无法取消**，会在原定时间照常响 —— 用户明明删了它。
     * 因此 id 必须在删除时被显式传递出去。
     */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
        onAlarmsChanged(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(enabled = enabled))
        onAlarmsChanged(null)
    }

    /**
     * 把一次性闹钟关掉，但**不**触发重算。
     *
     * 供 AlarmReceiver 使用：响铃后它自己会调 scheduleAll()，若这里也触发一次
     * 就是重复劳动，而且会让"响铃后重算"的调用时机变得难以追踪。
     */
    suspend fun disableSilently(id: Long) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(enabled = false))
    }
}
```

- [ ] **Step 6: 写依赖装配**

`app/src/main/java/com/example/clock/AppGraph.kt`

```kotlin
package com.example.clock

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.clock.data.AlarmDatabase
import com.example.clock.data.AlarmRepository

/**
 * 手写的依赖装配。
 *
 * 只有两个对象（Repository、Scheduler），引入 Hilt 的收益远小于它的复杂度。
 */
object AppGraph {

    @Volatile
    private var repositoryRef: AlarmRepository? = null

    fun repository(context: Context): AlarmRepository =
        repositoryRef ?: synchronized(this) {
            repositoryRef ?: AlarmRepository(
                dao = AlarmDatabase.get(context).alarmDao(),
                // Task 7 会把它换成 { scheduler(context).scheduleAll() }
                onAlarmsChanged = { },
            ).also { repositoryRef = it }
        }

    /**
     * 仅供测试：清空全部单例。
     *
     * Robolectric 每个测试都新建 Application，但 object 的静态字段不会重置。
     * 不调用它，后一个测试会拿到上一个测试遗留的、绑定了失效 Context 的实例。
     */
    @VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            repositoryRef = null
        }
        AlarmDatabase.resetForTesting()
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.data.AlarmRepositoryTest" --no-daemon
```

预期：13 个测试 PASS。

- [ ] **Step 8: 确认 Room schema 已导出**

```powershell
Get-ChildItem app\schemas\com.example.clock.data.AlarmDatabase\1.json
```

预期：文件存在。**把它一起提交** —— 日后写数据库迁移时需要它做对比。

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/clock/data/ app/src/main/java/com/example/clock/AppGraph.kt app/src/test/java/com/example/clock/data/AlarmRepositoryTest.kt app/schemas/
git commit -m "feat(data): 新增 DAO、Room 数据库、Repository 与手写依赖装配"
```

---

### Task 7: AlarmScheduler —— 唯一的重算入口

**Files:**
- Create: `app/src/main/java/com/example/clock/alarm/AlarmScheduler.kt`
- Modify: `app/src/main/java/com/example/clock/AppGraph.kt`
- Test: `app/src/test/java/com/example/clock/alarm/AlarmSchedulerTest.kt`

**Interfaces:**
- Consumes: `AlarmRepository`（Task 6）、`NextTrigger.next`（Task 3）、`Alarm`（Task 4）
- Produces:
  - `class AlarmScheduler(context: Context, repository: AlarmRepository)`
  - `suspend fun scheduleAll()` —— 清掉全部陈旧注册后，为所有已启用闹钟注册
  - `fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now())` —— 注册单个
  - `fun cancel(alarmId: Long)` —— 取消单个
  - `object AlarmScheduler.Companion`：`const val ACTION_FIRE_ALARM`、`const val EXTRA_ALARM_ID`、`const val EXTRA_HOUR`、`const val EXTRA_MINUTE`、`const val EXTRA_LABEL`、`const val EXTRA_VIBRATE`、`const val EXTRA_IS_SNOOZE`
  - `AppGraph.scheduler(context): AlarmScheduler`

> `AlarmScheduler` 引用的 `AlarmReceiver` 类在 Task 13 才创建。为了让本任务能独立编译，**Step 3 先创建一个最小的 `AlarmReceiver` 占位**（只有类和 `onReceive` 空实现），Task 13 再把它填满。这是刻意的顺序安排：调度比响应更值得先单独验证。

- [ ] **Step 1: 写失败的测试**

用 Robolectric 的 `ShadowAlarmManager` 断言注册结果。

`app/src/test/java/com/example/clock/alarm/AlarmSchedulerTest.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.clock.data.AlarmDatabase
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var alarmManager: AlarmManager
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-12 是周一 06:00，所有测试的基准 now。 */
    private val mondayMorning = ZonedDateTime.of(2026, 10, 12, 6, 0, 0, 0, zone)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        // setAutoSchedule 是 ShadowAlarmManager 的**静态**方法，不能经由
        // shadowOf(...) 得到的实例调用 —— 那样写编译不过。
        ShadowAlarmManager.setAutoSchedule(false)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // 按 AppGraph 的方式接线：删除时先把 id 交给调度器取消，再整体重算。
        // 这样测试覆盖的是真实的删除路径，而不是一个人造替身。
        repository = AlarmRepository(db.alarmDao()) { removedId ->
            if (removedId != null) scheduler.cancel(removedId)
            scheduler.scheduleAll()
        }
        scheduler = AlarmScheduler(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `注册的触发时刻与 NextTrigger 计算一致`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0))

        scheduler.scheduleAll(mondayMorning)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull("应当注册了一个闹钟", scheduled)
        val expected = NextTrigger.next(7, 0, RepeatDays.NONE_MASK, mondayMorning)
        assertEquals(expected.toInstant().toEpochMilli(), scheduled!!.triggerAtTime)
    }

    @Test
    fun `已禁用的闹钟不被注册`() = runTest {
        repository.save(Alarm(hour = 7, minute = 0, enabled = false))

        scheduler.scheduleAll(mondayMorning)

        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `多个闹钟各自注册，最近的那个排在最前`() = runTest {
        // setAlarmClock 注册的多个闹钟在系统中并存，状态栏显示最早的那个。
        // 这里断言的是"最早的那个确实被注册了"，与注册顺序无关。
        repository.save(Alarm(hour = 9, minute = 0))
        repository.save(Alarm(hour = 7, minute = 0))

        scheduler.scheduleAll(mondayMorning)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        val expected = NextTrigger.next(7, 0, RepeatDays.NONE_MASK, mondayMorning)
        assertEquals(expected.toInstant().toEpochMilli(), scheduled!!.triggerAtTime)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `删除闹钟会连它的注册一起取消`() = runTest {
        // 这条覆盖的是"幽灵闹钟"：用户删了闹钟，但它仍然在原定时间响。
        // 根因是 scheduleAll 只能取消"数据库里还存在的"闹钟的注册。
        val id = repository.save(Alarm(hour = 7, minute = 0))
        scheduler.scheduleAll(mondayMorning)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        repository.delete(id)

        assertNull(
            "闹钟被删除后不应还留着注册，实际 ${shadowOf(alarmManager).scheduledAlarms.size} 个",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `禁用闹钟后重算会取消它的注册`() = runTest {
        val id = repository.save(Alarm(hour = 7, minute = 0))
        scheduler.scheduleAll(mondayMorning)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        repository.setEnabled(id, false)
        scheduler.scheduleAll(mondayMorning)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `重复闹钟注册的是下一个命中的星期`() = runTest {
        val tuesdayMorning = ZonedDateTime.of(2026, 10, 13, 8, 0, 0, 0, zone)   // 周二 08:00
        val mask = RepeatDays.WEEKDAYS_MASK
        repository.save(Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(mask)))

        scheduler.scheduleAll(tuesdayMorning)

        val expected = NextTrigger.next(7, 0, mask, tuesdayMorning)
        assertEquals(
            expected.toInstant().toEpochMilli(),
            shadowOf(alarmManager).peekNextScheduledAlarm()!!.triggerAtTime,
        )
        // 周二 08:00 之后的第一个工作日 07:00 是周三 07:00
        assertEquals(ZonedDateTime.of(2026, 10, 14, 7, 0, 0, 0, zone), expected)
    }

    @Test
    fun `空列表不注册任何闹钟`() = runTest {
        scheduler.scheduleAll(mondayMorning)
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }
}
```

> 本测试用真实的 in-memory Room 而不是手写替身。这样删除路径、调度路径、PendingIntent 身份三者是被一起验证的 —— 而"幽灵闹钟"这个 bug 恰恰只在这三者串起来时才会暴露。为此**不需要**修改 `AlarmRepository` 的可见性。

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.AlarmSchedulerTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'AlarmScheduler'`。

- [ ] **Step 3: 创建 AlarmReceiver 占位**

`AlarmScheduler` 引用的 `AlarmReceiver` 类要到 Task 13 才填满。为了让本任务能独立编译，先建一个空壳。这是刻意的顺序安排：调度比响应更值得先单独验证。

创建 `app/src/main/java/com/example/clock/alarm/AlarmReceiver.kt`：

```kotlin
package com.example.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 到点被 AlarmManager 唤醒。完整实现在 Task 13。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Task 13 填充
    }
}
```

- [ ] **Step 4: 写实现**

`app/src/main/java/com/example/clock/alarm/AlarmScheduler.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import java.time.ZonedDateTime

/**
 * 闹钟调度。**这是唯一的重算入口。**
 *
 * 做的永远是同一件事：读数据库所有已启用的闹钟 → 逐个算下次触发时刻 →
 * 用 setAlarmClock() 注册。
 *
 * 它有且仅有 6 个调用时机（全部通过 scheduleAll()）：开机、应用更新、改时间、
 * 改时区、闹钟增删改、闹钟响铃后。
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /**
     * 清掉全部陈旧注册，再为所有已启用的闹钟重新注册。
     *
     * 先取消全部（含已禁用的）再注册，是为了保证任何残留的旧注册都不会
     * 在用户改完设置后仍然生效。
     */
    suspend fun scheduleAll(now: ZonedDateTime = ZonedDateTime.now()) {
        repository.getAll().forEach { cancel(it.id) }
        repository.getEnabled().forEach { schedule(it, now) }
    }

    /** 为单个闹钟注册下次触发。 */
    fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()) {
        val triggerAt = NextTrigger.next(alarm.hour, alarm.minute, alarm.repeatDays.mask, now)

        // FLAG_UPDATE_CURRENT 下 getBroadcast 必然返回非 null，这里的 ?: return
        // 只是为了让可空类型收敛，不是真实的失败分支。
        val pi = pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt.toInstant().toEpochMilli(), showIntent()),
            pi,
        )
    }

    /** 取消某个闹钟的注册。不存在时静默返回。 */
    fun cancel(alarmId: Long) {
        val pi = pendingIntent(alarmId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /**
     * 点击系统状态栏闹钟图标时打开的界面。
     *
     * 刻意用 getLaunchIntentForPackage 而不是直接引用 MainActivity —— 后者会让
     * alarm 层依赖 ui 层，破坏本项目的分层约束。
     */
    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pendingIntent(alarm: Alarm, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarm.id, alarm, extraFlags)

    private fun pendingIntent(alarmId: Long, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarmId, null, extraFlags)

    private fun pendingIntentInternal(
        alarmId: Long,
        alarm: Alarm?,
        extraFlags: Int,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            // 唯一的 data URI：与 requestCode 一起保证每个闹钟的 PendingIntent 相互独立，
            // 否则所有闹钟会共用同一个，改一个等于改全部。
            data = Uri.parse("clock://alarm/$alarmId")
            putExtra(EXTRA_ALARM_ID, alarmId)
            alarm?.let {
                putExtra(EXTRA_HOUR, it.hour)
                putExtra(EXTRA_MINUTE, it.minute)
                putExtra(EXTRA_LABEL, it.label)
                putExtra(EXTRA_VIBRATE, it.vibrate)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE_ALARM = "com.example.clock.action.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_HOUR = "extra_hour"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_VIBRATE = "extra_vibrate"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"

        private const val SHOW_REQUEST_CODE = 99_999
    }
}
```

> `FLAG_NO_CREATE` 时若 PendingIntent 不存在，`getBroadcast` 返回 null；因此 `pendingIntent` 的返回类型是可空的。取消时若为 null 直接返回。

- [ ] **Step 5: 在 AppGraph 中接上 Scheduler**

把 `AppGraph.kt` 补成：

```kotlin
package com.example.clock

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.clock.alarm.AlarmScheduler
import com.example.clock.data.AlarmDatabase
import com.example.clock.data.AlarmRepository

object AppGraph {

    @Volatile
    private var repositoryRef: AlarmRepository? = null

    @Volatile
    private var schedulerRef: AlarmScheduler? = null

    fun repository(context: Context): AlarmRepository =
        repositoryRef ?: synchronized(this) {
            repositoryRef ?: AlarmRepository(
                dao = AlarmDatabase.get(context).alarmDao(),
                onAlarmsChanged = { removedId ->
                    // 删除时先把 id 交给调度器。跳掉这一步，被删闹钟的注册就再也
                    // 取消不掉，会在原定时间照常响 —— 见 AlarmRepository.delete。
                    if (removedId != null) scheduler(context).cancel(removedId)
                    scheduler(context).scheduleAll()
                },
            ).also { repositoryRef = it }
        }

    fun scheduler(context: Context): AlarmScheduler =
        schedulerRef ?: synchronized(this) {
            schedulerRef ?: AlarmScheduler(
                context = context.applicationContext,
                repository = repository(context),
            ).also { schedulerRef = it }
        }

    /** 仅供测试：清空全部单例。理由见 Task 6 的同名函数。 */
    @VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            repositoryRef = null
            schedulerRef = null
        }
        AlarmDatabase.resetForTesting()
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.AlarmSchedulerTest" --no-daemon
```

预期：7 个测试 PASS。

- [ ] **Step 7: 跑全量测试确认无回归**

```powershell
.\gradlew.bat test --no-daemon
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/ app/src/main/java/com/example/clock/AppGraph.kt app/src/main/java/com/example/clock/data/AlarmRepository.kt app/src/test/java/com/example/clock/alarm/AlarmSchedulerTest.kt
git commit -m "feat(alarm): 新增 AlarmScheduler，统一重算入口"
```

---

### Task 8: RingAudioController —— 铃声播放的单一持有者

**Files:**
- Create: `app/src/main/java/com/example/clock/alarm/AlarmPlayer.kt`
- Create: `app/src/main/java/com/example/clock/alarm/RingAudioController.kt`
- Test: `app/src/test/java/com/example/clock/alarm/RingAudioControllerTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `interface AlarmPlayer { fun start(vibrate: Boolean); fun stop() }`
  - `class RingAudioController(player: AlarmPlayer)`：`fun start(vibrate: Boolean)`、`fun stop()`、`val isRinging: Boolean`
  - `RingAudioController.Companion.get(context: Context): RingAudioController`
  - `class MediaPlayerAlarmPlayer(context: Context) : AlarmPlayer`

> **这是 Review Focus 第 5 条的落点。** 幂等性（重复 start 不产生第二路铃声）是前台服务与全屏 Activity 并存时不出双响的唯一保证，必须有测试。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/alarm/RingAudioControllerTest.kt`

```kotlin
package com.example.clock.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RingAudioControllerTest {

    private class FakePlayer : AlarmPlayer {
        var startCount = 0
        var stopCount = 0
        var lastVibrate: Boolean? = null

        override fun start(vibrate: Boolean) {
            startCount++
            lastVibrate = vibrate
        }

        override fun stop() {
            stopCount++
        }
    }

    /**
     * 一个 start() 必定抛异常的播放器。
     *
     * 用来钉住"失败的 start 不许留下关不掉的铃声"这条不变量 —— FakePlayer 永远成功，
     * 结构上根本测不到失败路径。
     */
    private class ThrowingPlayer : AlarmPlayer {
        var startCount = 0
        var stopCount = 0

        override fun start(vibrate: Boolean) {
            startCount++
            throw SecurityException("模拟播放途中失败")
        }

        override fun stop() {
            stopCount++
        }
    }

    private lateinit var player: FakePlayer
    private lateinit var controller: RingAudioController

    @Before
    fun setUp() {
        player = FakePlayer()
        controller = RingAudioController(player)
    }

    @Test
    fun `首次 start 会真正播放`() {
        controller.start(vibrate = true)

        assertEquals(1, player.startCount)
        assertTrue(controller.isRinging)
        assertEquals(true, player.lastVibrate)
    }

    @Test
    fun `重复 start 不会产生第二路铃声`() {
        controller.start(vibrate = true)
        controller.start(vibrate = true)
        controller.start(vibrate = true)

        assertEquals("重复 start 只应真正播放一次", 1, player.startCount)
        assertTrue(controller.isRinging)
    }

    @Test
    fun `重复 stop 不会报错也不会重复停止`() {
        controller.start(vibrate = true)
        controller.stop()
        controller.stop()

        assertEquals(1, player.stopCount)
        assertFalse(controller.isRinging)
    }

    @Test
    fun `未 start 就 stop 是安全的`() {
        controller.stop()
        assertEquals(0, player.stopCount)
        assertFalse(controller.isRinging)
    }

    @Test
    fun `stop 之后可以再次 start`() {
        controller.start(vibrate = true)
        controller.stop()
        controller.start(vibrate = false)

        assertEquals(2, player.startCount)
        assertEquals(1, player.stopCount)
        assertTrue(controller.isRinging)
        assertEquals(false, player.lastVibrate)
    }

    @Test
    fun `震动开关被原样传给播放器`() {
        controller.start(vibrate = false)
        assertEquals(false, player.lastVibrate)

        controller.stop()
        controller.start(vibrate = true)
        assertEquals(true, player.lastVibrate)
    }

    @Test
    fun `多线程并发 start 只播放一次`() {
        val threads = (1..8).map {
            Thread { controller.start(vibrate = true) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发 start 也只应播放一次", 1, player.startCount)
    }

    @Test
    fun `播放器抛异常时不会留下关不掉的铃声`() {
        val throwing = ThrowingPlayer()
        val c = RingAudioController(throwing)

        assertThrows(SecurityException::class.java) { c.start(vibrate = true) }

        assertEquals("失败的 start 必须自己收尾，把播放器停掉", 1, throwing.stopCount)
        assertFalse("失败的 start 之后不能还声称在响铃", c.isRinging)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingAudioControllerTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'AlarmPlayer'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/alarm/AlarmPlayer.kt`

```kotlin
package com.example.clock.alarm

/**
 * 播放铃声与震动的抽象。
 *
 * 抽出这个接口的唯一目的是让 [RingAudioController] 的幂等性可以在 JVM 上测试 ——
 * MediaPlayer 与 Vibrator 在单元测试里都不可用。
 */
interface AlarmPlayer {
    fun start(vibrate: Boolean)
    fun stop()
}
```

`app/src/main/java/com/example/clock/alarm/RingAudioController.kt`

```kotlin
package com.example.clock.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 铃声播放的**单一持有者**。
 *
 * 前台服务（RingService）和全屏界面（RingActivity）都会调用 [start] —— 谁先到
 * 谁真正播放，后到的是一次空操作。这是"不许出现两路铃声重叠"这一约束的实现方式，
 * 也是前台服务启动失败时能降级给 Activity 播放的前提。
 */
class RingAudioController(private val player: AlarmPlayer) {

    private val lock = Any()
    private var ringing = false

    val isRinging: Boolean get() = synchronized(lock) { ringing }

    /** 开始响铃。已在响铃时是空操作。 */
    fun start(vibrate: Boolean) {
        synchronized(lock) {
            if (ringing) return
            // 先把标志置为"响铃中"再真正播放。顺序不能反：mediaPlayer.start() 已经播出
            // 声音之后，startVibrating() 仍可能抛异常（例如震动权限缺失）。若标志还是
            // false，stop() 会在 `if (!ringing) return` 上提前返回，用户就关不掉这通铃声了。
            ringing = true
            try {
                player.start(vibrate)
            } catch (t: Throwable) {
                // 播放器可能已经播了一半，尽力收尾，回滚标志，再把异常原样抛出。
                runCatching { player.stop() }
                ringing = false
                throw t
            }
        }
    }

    /** 停止响铃。本来就没在响时是空操作。 */
    fun stop() {
        synchronized(lock) {
            if (!ringing) return
            player.stop()
            ringing = false
        }
    }

    companion object {
        @Volatile
        private var instance: RingAudioController? = null

        fun get(context: Context): RingAudioController =
            instance ?: synchronized(this) {
                instance ?: RingAudioController(
                    MediaPlayerAlarmPlayer(context.applicationContext),
                ).also { instance = it }
            }
    }
}

/**
 * 用 MediaPlayer 播放系统默认闹钟铃声，并用 Vibrator 震动。
 */
class MediaPlayerAlarmPlayer(private val context: Context) : AlarmPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun start(vibrate: Boolean) {
        // 先释放上一轮，避免"上一次铃声还在响时新的触发到来"造成叠音。
        releasePlayer()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        try {
            // 先把实例赋给字段，再调用会抛异常的 setDataSource / prepare / start。
            // 不能写成 `mediaPlayer = MediaPlayer().apply { ... }`：apply 里任何一步抛异常，
            // 整个赋值都会被跳过，字段停在 null，新建的 MediaPlayer 从此无人能释放，
            // 反复失败会一直占着音频编解码器。
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            if (vibrate) startVibrating()
        } catch (t: Throwable) {
            // 半途失败也要把已经建好的 MediaPlayer 放掉，再把异常原样抛出。
            releasePlayer()
            stopVibrating()
            throw t
        }
    }

    override fun stop() {
        releasePlayer()
        stopVibrating()
    }

    private fun releasePlayer() {
        mediaPlayer?.run {
            try {
                if (isPlaying) stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "停止 MediaPlayer 时状态异常，忽略", e)
            }
            release()
        }
        mediaPlayer = null
    }

    private fun startVibrating() {
        val v = obtainVibrator() ?: return
        vibrator = v
        // 响 1 秒、停 1 秒，循环往复。
        val pattern = longArrayOf(0L, 1000L, 1000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    private fun stopVibrating() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun obtainVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        const val TAG = "MediaPlayerAlarmPlayer"
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingAudioControllerTest" --no-daemon
```

预期：8 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/AlarmPlayer.kt app/src/main/java/com/example/clock/alarm/RingAudioController.kt app/src/test/java/com/example/clock/alarm/RingAudioControllerTest.kt
git commit -m "feat(alarm): 新增 RingAudioController，保证铃声播放幂等不叠音"
```

---

### Task 9: RingingAlarm —— 响铃数据与 Intent 打包

**Files:**
- Create: `app/src/main/java/com/example/clock/alarm/RingingAlarm.kt`
- Test: `app/src/test/java/com/example/clock/alarm/RingingAlarmTest.kt`

**Interfaces:**
- Consumes: `AlarmScheduler` 的 `EXTRA_*` 常量（Task 7）
- Produces:
  - `data class RingingAlarm(id: Long, hour: Int, minute: Int, label: String, vibrate: Boolean)`
  - `fun RingingAlarm.writeTo(intent: Intent)`
  - `RingingAlarm.Companion.from(intent: Intent): RingingAlarm?`（缺失或非法时返回 null）

> 把展示数据随 PendingIntent 一起带过来，而不是到点时查数据库，是为了让 `AlarmReceiver` 能在**任何数据库操作之前**就把界面拉起来 —— 响铃的延迟越短越好。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/alarm/RingingAlarmTest.kt`

```kotlin
package com.example.clock.alarm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingingAlarmTest {

    private fun emptyIntent() =
        Intent(ApplicationProvider.getApplicationContext(), AlarmReceiver::class.java)

    @Test
    fun `写入后能原样读出`() {
        val original = RingingAlarm(id = 5L, hour = 7, minute = 30, label = "起床", vibrate = false)
        val intent = emptyIntent()

        original.writeTo(intent)
        val restored = RingingAlarm.from(intent)

        assertEquals(original, restored)
    }

    @Test
    fun `缺少 alarmId 时返回 null`() {
        assertNull(RingingAlarm.from(emptyIntent()))
    }

    @Test
    fun `alarmId 非法时返回 null`() {
        val intent = emptyIntent().putExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `缺少 hour 时返回 null`() {
        val intent = emptyIntent().putExtra(AlarmScheduler.EXTRA_ALARM_ID, 5L)
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `小时越界时返回 null`() {
        val intent = emptyIntent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 5L)
            putExtra(AlarmScheduler.EXTRA_HOUR, 24)
            putExtra(AlarmScheduler.EXTRA_MINUTE, 0)
        }
        assertNull(RingingAlarm.from(intent))
    }

    @Test
    fun `空标签也能往返`() {
        val original = RingingAlarm(id = 1L, hour = 6, minute = 0, label = "", vibrate = true)
        val intent = emptyIntent()
        original.writeTo(intent)
        assertEquals("", RingingAlarm.from(intent)?.label)
    }

    @Test
    fun `写出的 Intent 与 AlarmScheduler 约定的 key 一致`() {
        val intent = emptyIntent()
        RingingAlarm(id = 3L, hour = 8, minute = 15, label = "x", vibrate = true).writeTo(intent)

        assertEquals(3L, intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(8, intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1))
        assertEquals(15, intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1))
        assertEquals("x", intent.getStringExtra(AlarmScheduler.EXTRA_LABEL))
        assertEquals(true, intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, false))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingingAlarmTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'RingingAlarm'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/alarm/RingingAlarm.kt`

```kotlin
package com.example.clock.alarm

import android.content.Intent

/**
 * 一次响铃所需的全部展示数据。
 *
 * 这些值随 PendingIntent 一起传递，使 AlarmReceiver 不必查数据库就能拉起界面。
 */
data class RingingAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val vibrate: Boolean,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
        intent.putExtra(AlarmScheduler.EXTRA_HOUR, hour)
        intent.putExtra(AlarmScheduler.EXTRA_MINUTE, minute)
        intent.putExtra(AlarmScheduler.EXTRA_LABEL, label)
        intent.putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
    }

    companion object {
        /** 从 Intent 还原。数据缺失或非法时返回 null，调用方应直接放弃这次响铃。 */
        fun from(intent: Intent): RingingAlarm? {
            val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
            if (id <= 0L) return null

            if (!intent.hasExtra(AlarmScheduler.EXTRA_HOUR)) return null
            if (!intent.hasExtra(AlarmScheduler.EXTRA_MINUTE)) return null
            val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
            val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)
            if (hour !in 0..23 || minute !in 0..59) return null

            return RingingAlarm(
                id = id,
                hour = hour,
                minute = minute,
                label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty(),
                vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true),
            )
        }

    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingingAlarmTest" --no-daemon
```

预期：7 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/RingingAlarm.kt app/src/test/java/com/example/clock/alarm/RingingAlarmTest.kt
git commit -m "feat(alarm): 新增 RingingAlarm，响铃数据随 Intent 传递"
```

---

### Task 10: SnoozeManager —— 贪睡

**Files:**
- Create: `app/src/main/java/com/example/clock/alarm/SnoozeManager.kt`
- Test: `app/src/test/java/com/example/clock/alarm/SnoozeManagerTest.kt`

**Interfaces:**
- Consumes: `RingingAlarm`（Task 9）、`AlarmScheduler` 的常量（Task 7）
- Produces:
  - `class SnoozeManager(context: Context)`：`fun snooze(ringing: RingingAlarm, nowMillis: Long = System.currentTimeMillis())`、`fun cancel(alarmId: Long)`
  - `SnoozeManager.Companion`：`const val SNOOZE_MINUTES = 10L`、`const val SNOOZE_REQUEST_OFFSET = 100_000`

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/alarm/SnoozeManagerTest.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class SnoozeManagerTest {

    private lateinit var context: Context
    private lateinit var snoozeManager: SnoozeManager
    private lateinit var alarmManager: AlarmManager

    private val ringing = RingingAlarm(id = 3L, hour = 7, minute = 0, label = "起床", vibrate = false)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        snoozeManager = SnoozeManager(context)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    @Test
    fun `贪睡注册的是十分钟后`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertEquals(now + 10 * 60_000L, scheduled!!.triggerAtTime)
    }

    @Test
    fun `贪睡时长常量是十分钟`() {
        assertEquals(10L, SnoozeManager.SNOOZE_MINUTES)
    }

    @Test
    fun `贪睡注册用的 requestCode 与普通闹钟不冲突`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        // 普通闹钟的 requestCode 是 alarm.id 本身（3），贪睡必须是另一个值。
        assertEquals(
            SnoozeManager.SNOOZE_REQUEST_OFFSET + ringing.id.toInt(),
            shadowOf(scheduled!!.operation).requestCode,
        )
    }

    @Test
    fun `取消贪睡后没有待触发的闹钟`() {
        val now = 1_000_000_000_000L
        snoozeManager.snooze(ringing, nowMillis = now)
        snoozeManager.cancel(ringing.id)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `取消不存在的贪睡是安全的`() {
        snoozeManager.cancel(999L)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `重复贪睡会重置计时`() {
        val first = 1_000_000_000_000L
        val second = first + 60_000L
        snoozeManager.snooze(ringing, nowMillis = first)
        snoozeManager.snooze(ringing, nowMillis = second)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertEquals(second + 10 * 60_000L, scheduled!!.triggerAtTime)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.SnoozeManagerTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'SnoozeManager'`。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/example/clock/alarm/SnoozeManager.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 贪睡。
 *
 * 刻意**不写数据库、不修改闹钟记录** —— 只是向 AlarmManager 注册一个十分钟后的
 * 一次性触发。响铃结束这个注册自然失效，用户的闹钟设置完全不受影响。
 */
class SnoozeManager(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /** 注册 [SNOOZE_MINUTES] 分钟后的再次响铃。重复调用会重置计时。 */
    fun snooze(ringing: RingingAlarm, nowMillis: Long = System.currentTimeMillis()) {
        val triggerAt = nowMillis + SNOOZE_MINUTES * 60_000L

        // FLAG_UPDATE_CURRENT 下 getBroadcast 必然返回非 null，这里的 ?: return
        // 只是为了让可空类型收敛，不是真实的失败分支。
        // 注意不能把 pendingIntent(ringing) 直接塞进 setAlarmClock —— 它返回
        // PendingIntent?，而 setAlarmClock 的第二个参数在 SDK 35 的 stub 上标注了
        // @NonNull，直接传会编译不过。
        val pi = pendingIntent(ringing) ?: return

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent()),
            pi,
        )
    }

    /** 取消某个闹钟对应的贪睡。不存在时静默返回。 */
    fun cancel(alarmId: Long) {
        val pi = pendingIntent(alarmId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pendingIntent(ringing: RingingAlarm): PendingIntent? =
        pendingIntentInternal(ringing.id, ringing, PendingIntent.FLAG_UPDATE_CURRENT)

    private fun pendingIntent(alarmId: Long, extraFlags: Int): PendingIntent? =
        pendingIntentInternal(alarmId, null, extraFlags)

    private fun pendingIntentInternal(
        alarmId: Long,
        ringing: RingingAlarm?,
        extraFlags: Int,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
            data = Uri.parse("clock://snooze/$alarmId")
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, true)
            ringing?.writeTo(this) ?: putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_OFFSET + alarmId.toInt(),
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val SNOOZE_MINUTES = 10L

        /**
         * 贪睡的 requestCode 偏移量。
         * 与普通闹钟的 requestCode（alarm.id）拉开距离，避免两者互相覆盖。
         */
        const val SNOOZE_REQUEST_OFFSET = 100_000

        private const val SHOW_REQUEST_CODE = 99_998
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.SnoozeManagerTest" --no-daemon
```

预期：6 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/SnoozeManager.kt app/src/test/java/com/example/clock/alarm/SnoozeManagerTest.kt
git commit -m "feat(alarm): 新增 SnoozeManager，贪睡不落库"
```

---

### Task 11: RingService —— 前台服务与常驻通知

**Files:**
- Create: `app/src/main/java/com/example/clock/alarm/RingNotification.kt`
- Create: `app/src/main/java/com/example/clock/alarm/RingService.kt`
- Test: `app/src/test/java/com/example/clock/alarm/RingNotificationTest.kt`

**Interfaces:**
- Consumes: `RingingAlarm`（Task 9）、`RingAudioController`（Task 8）、`SnoozeManager`（Task 10）
- Produces:
  - `object RingNotification`：`const val CHANNEL_ID = "alarm_ringing"`、`const val NOTIFICATION_ID = 1001`、`fun ensureChannel(context: Context)`、`fun build(context: Context, ringing: RingingAlarm): Notification`
  - `class RingService : Service`，`RingService.Companion.intent(context: Context, ringing: RingingAlarm): Intent`
  - `RingService.Companion.start(context: Context, ringing: RingingAlarm)` —— 内部用 `ContextCompat.startForegroundService`，且**不吞异常**：调用方（Task 13 的 `AlarmReceiver`）需要知道失败才能走降级路径。Task 13 的 `serviceStarter` 默认值就是 `RingService::start`。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/example/clock/alarm/RingNotificationTest.kt`

```kotlin
package com.example.clock.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingNotificationTest {

    // 注意：查询渠道一律走 NotificationManager 的**公开** API `manager.getNotificationChannel(id)`
    // （API 26+），不要用 `shadowOf(manager)`。ShadowNotificationManager 里那两个
    // getNotificationChannel 重载都是 protected，而本测试类不在 org.robolectric.shadows 包内，
    // 所以 `shadowOf(manager).getNotificationChannel(...)` 根本编译不过：
    //   e: RingNotificationTest.kt:33:41 Cannot access 'fun getNotificationChannel(p0: String!):
    //      NotificationChannel!': it is protected in 'org/robolectric/shadows/ShadowNotificationManager'.
    // 公开 API 走的是同一份 shadow 存储，语义完全一致。

    private lateinit var context: Context

    private val ringing = RingingAlarm(id = 3L, hour = 7, minute = 30, label = "起床", vibrate = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `ensureChannel 创建了响铃通知渠道`() {
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RingNotification.CHANNEL_ID)
        assertNotNull("响铃渠道应当被创建", channel)
    }

    @Test
    fun `渠道的重要性足以横幅提示`() {
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RingNotification.CHANNEL_ID)!!
        assertTrue(
            "响铃通知必须能横幅提示，实际 importance=${channel.importance}",
            channel.importance >= NotificationManager.IMPORTANCE_HIGH,
        )
    }

    @Test
    fun `重复 ensureChannel 不抛异常`() {
        RingNotification.ensureChannel(context)
        RingNotification.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(RingNotification.CHANNEL_ID))
    }

    @Test
    fun `通知内容含闹钟时间与标签`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        assertTrue("标题应含时间，实际为 $title", title.contains("07:30"))
        assertTrue("正文应含标签，实际为 $text", text.contains("起床"))
    }

    @Test
    fun `通知带两个操作按钮`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertEquals(2, notification.actions.size)
    }

    @Test
    fun `通知是常驻的且分类为闹钟`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertTrue("应是常驻通知", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
    }

    @Test
    fun `通知设置了全屏意图以便在锁屏上弹出`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(context, ringing)

        assertNotNull("必须设置 fullScreenIntent", notification.fullScreenIntent)
    }

    @Test
    fun `标签为空时不显示空的正文行`() {
        RingNotification.ensureChannel(context)
        val notification = RingNotification.build(
            context,
            ringing.copy(label = ""),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("正文不应为空，实际为 '$text'", text.isNotBlank())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingNotificationTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'RingNotification'`。

- [ ] **Step 3: 写通知构建**

`app/src/main/java/com/example/clock/alarm/RingNotification.kt`

```kotlin
package com.example.clock.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 响铃通知。它有三个作用：
 * 1. 前台服务的载体（没有通知就不能是前台服务）
 * 2. 全屏 Activity 被系统抑制时的最后一道防线
 * 3. 提供不进入界面就能关闭/贪睡的入口
 */
object RingNotification {

    const val CHANNEL_ID = "alarm_ringing"
    const val NOTIFICATION_ID = 1001

    private const val CHANNEL_NAME = "闹钟响铃"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "闹钟到点时的响铃提示"
            setShowBadge(true)
            enableVibration(false)   // 震动由 MediaPlayerAlarmPlayer 自己控制
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, ringing: RingingAlarm): Notification {
        val timeText = formatTime(ringing.hour, ringing.minute)
        val bodyText = ringing.label.ifBlank { "闹钟时间到了" }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$timeText 闹钟")
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(ringActivityIntent(context, ringing))
            // 锁屏上直接弹出响铃界面。与 AlarmReceiver 直接拉 Activity 形成双保险，
            // RingActivity 声明为 singleTask，重复触发只会走 onNewIntent。
            .setFullScreenIntent(ringActivityIntent(context, ringing), true)
            .addAction(
                0,
                "贪睡 ${SnoozeManager.SNOOZE_MINUTES} 分钟",
                serviceAction(context, RingService.ACTION_SNOOZE, ringing),
            )
            .addAction(
                0,
                "关闭",
                serviceAction(context, RingService.ACTION_STOP, ringing),
            )
            .build()
    }

    private fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)

    /** 指向全屏响铃界面。用 Intent 的 action 而非类引用，保持 alarm 层不依赖 ui 层。 */
    private fun ringActivityIntent(
        context: Context,
        ringing: RingingAlarm,
    ): PendingIntent {
        val intent = Intent(RingService.ACTION_SHOW_RING_UI).apply {
            setPackage(context.packageName)
            ringing.writeTo(this)
        }
        return PendingIntent.getActivity(
            context,
            ringing.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceAction(
        context: Context,
        action: String,
        ringing: RingingAlarm,
    ): PendingIntent {
        val intent = Intent(context, RingService::class.java).apply {
            this.action = action
            ringing.writeTo(this)
        }
        return PendingIntent.getService(
            context,
            ringing.id.toInt() + action.hashCode().and(0xFFFF),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
```

> **`ringActivityIntent` 的说明**：这里用 action + `setPackage` 而不是 `Intent(context, RingActivity::class.java)`，同样是为了不破坏 `alarm/` 不依赖 `ui/` 的分层约束。`RingActivity` 在 manifest 里声明一个匹配 `ACTION_SHOW_RING_UI` 的 intent-filter（Task 12 Step 5）。`FLAG_IMMUTABLE` 的 PendingIntent 用隐式 Intent 是允许的 —— 限制只针对 `FLAG_MUTABLE`。

- [ ] **Step 4: 写服务**

`app/src/main/java/com/example/clock/alarm/RingService.kt`

```kotlin
package com.example.clock.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 响铃前台服务。
 *
 * 它**不是**铃声的持有者（那是 RingAudioController），职责只有两个：
 * 1. 用常驻通知把进程提升为前台，降低被系统回收的概率
 * 2. 提供通知栏上的关闭/贪睡入口
 *
 * 正因为铃声音频不归它独占，前台服务启动失败时（见 spec 5.6）铃声仍能由
 * RingActivity 播放，不会出现"服务没了就彻底不响"。
 */
class RingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ringing = intent?.let { RingingAlarm.from(it) }
        if (ringing == null) {
            // 数据不完整，无法响铃也无法展示，直接退出。
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                RingAudioController.get(this).stop()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                SnoozeManager(this).snooze(ringing)
                RingAudioController.get(this).stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // 默认动作：进入响铃状态。
        // Android 12+ 要求 startForegroundService 后必须尽快调用 startForeground，
        // 因此放在最前面，晚于它做任何事都可能触发 ANR。
        try {
            RingNotification.ensureChannel(this)
            startForeground(RingNotification.NOTIFICATION_ID, RingNotification.build(this, ringing))
        } catch (e: Exception) {
            // 例如用户关闭了通知权限导致通知无法展示。此时服务退化为普通后台服务，
            // 铃声响不响交给 RingActivity，不让异常中断流程。
            Log.w(TAG, "进入前台失败，降级为普通服务", e)
        }

        // 播放失败不要让服务崩掉。RingAudioController.start() 失败时会把状态回滚成
        // "未在响铃"再原样抛出，所以 RingActivity.onStart() 里那次 start() 会重新尝试播放 ——
        // 这正是 spec 5.6 想要的降级路径。因此这里只记录，不重抛。
        try {
            RingAudioController.get(this).start(ringing.vibrate)
        } catch (t: Throwable) {
            Log.w(TAG, "开始播放铃声失败，交给响铃界面兜底", t)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RingAudioController.get(this).stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingService"

        const val ACTION_STOP = "com.example.clock.action.STOP_RINGING"
        const val ACTION_SNOOZE = "com.example.clock.action.SNOOZE"
        const val ACTION_SHOW_RING_UI = "com.example.clock.action.SHOW_RING_UI"

        fun intent(context: Context, ringing: RingingAlarm): Intent =
            Intent(context, RingService::class.java).apply { ringing.writeTo(this) }

        /**
         * 启动前台服务。不在此处吞异常 —— 调用方（AlarmReceiver）需要知道是否
         * 失败，以便走降级路径。
         */
        fun start(context: Context, ringing: RingingAlarm) {
            ContextCompat.startForegroundService(context, intent(context, ringing))
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.RingNotificationTest" --no-daemon
```

预期：8 个测试 PASS。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/RingNotification.kt app/src/main/java/com/example/clock/alarm/RingService.kt app/src/test/java/com/example/clock/alarm/RingNotificationTest.kt
git commit -m "feat(alarm): 新增响铃前台服务与常驻通知"
```

---

### Task 12: RingActivity 与响铃界面

**Files:**
- Create: `app/src/main/java/com/example/clock/ui/ring/RingActivity.kt`
- Create: `app/src/main/java/com/example/clock/ui/ring/RingScreen.kt`

**Interfaces:**
- Consumes: `RingingAlarm`（Task 9）、`RingAudioController`（Task 8）、`SnoozeManager`（Task 10）、`RingService`（Task 11）、`ClockTheme`（Task 1）
- Produces:
  - `class RingActivity : ComponentActivity`，`RingActivity.Companion.start(context: Context, ringing: RingingAlarm)`
  - `@Composable fun RingScreen(ringing: RingingAlarm, onSnooze: () -> Unit, onDismiss: () -> Unit)`

> **这是 Review Focus 第 5 条的另一半。** `RingActivity.onStart()` 会调用 `RingAudioController.start()`。若前台服务已经先启动了播放，这里是空操作；若服务启动失败，这里就是唯一的播放来源。两条路径共用同一个幂等控制器，因此不可能叠音。

- [ ] **Step 1: 写响铃界面**

`app/src/main/java/com/example/clock/ui/ring/RingScreen.kt`

```kotlin
package com.example.clock.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.clock.alarm.RingingAlarm
import com.example.clock.alarm.SnoozeManager

@Composable
fun RingScreen(
    ringing: RingingAlarm,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 响铃界面固定用深色，不跟随系统主题：无论白天黑夜，锁屏上都要一眼看清。
    val background = Color(0xFF101014)
    val onBackground = Color(0xFFF2F2F7)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "%02d:%02d".format(ringing.hour, ringing.minute),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = onBackground,
                textAlign = TextAlign.Center,
            )

            if (ringing.label.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = ringing.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onBackground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(64.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onBackground),
                ) {
                    Text("贪睡 ${SnoozeManager.SNOOZE_MINUTES} 分钟")
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
```

- [ ] **Step 2: 写 Activity**

`app/src/main/java/com/example/clock/ui/ring/RingActivity.kt`

```kotlin
package com.example.clock.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.example.clock.alarm.RingAudioController
import com.example.clock.alarm.RingService
import com.example.clock.alarm.RingingAlarm
import com.example.clock.alarm.SnoozeManager
import com.example.clock.ui.theme.ClockTheme

/**
 * 全屏响铃界面。独立 Activity，不参与导航图。
 *
 * 启动 Activity 不受"后台不能启动前台服务"那些限制，因此这是响铃链路里最可靠的
 * 一环 —— AlarmReceiver 会最先拉起它。
 */
class RingActivity : ComponentActivity() {

    private var ringing: RingingAlarm? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏之上直接显示，无需解锁；同时点亮屏幕。
        // 这两个方法是 API 27（O_MR1）才有的，而本项目 minSdk = 26：不加版本判断的话，
        // 在一台 Android 8.0 机器上一响铃就 NoSuchMethodError 崩溃 —— 那是这个 app
        // 最不能崩的时刻。API 26 上退回普通显示即可，不影响"闹钟一定会响"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val incoming = RingingAlarm.from(intent)
        if (incoming == null) {
            finish()
            return
        }
        ringing = incoming

        setContent {
            ClockTheme {
                // 返回键不关闭响铃：半睡半醒时误触返回键等于把闹钟静音了。
                BackHandler(enabled = true) { /* 刻意留空 */ }

                RingScreen(
                    ringing = incoming,
                    onSnooze = ::snooze,
                    onDismiss = ::dismiss,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        RingingAlarm.from(intent)?.let { ringing = it }
    }

    /**
     * 铃声音频的降级入口。
     *
     * 正常情况下 RingService 已经开始了播放，这里是一次空操作；若前台服务被系统
     * 拒绝启动（见 spec 5.6），这里就是唯一的播放来源。RingAudioController 的幂等性
     * 保证两者不会叠音。
     */
    override fun onStart() {
        super.onStart()
        // 这是响铃的兜底路径，也是最后一道。播放失败时不能让它把 Activity 带崩 ——
        // 界面留着，用户还能贪睡或关闭，总好过弹一个崩溃对话框。
        // 控制器失败时已把状态回滚为"未在响铃"，所以这里吞掉不会留下陈旧状态。
        ringing?.let {
            try {
                RingAudioController.get(this).start(it.vibrate)
            } catch (t: Throwable) {
                Log.w(TAG, "降级播放铃声也失败了", t)
            }
        }
    }

    private fun snooze() {
        ringing?.let { SnoozeManager(this).snooze(it) }
        stopEverything()
    }

    private fun dismiss() {
        stopEverything()
    }

    private fun stopEverything() {
        RingAudioController.get(this).stop()
        stopService(Intent(this, RingService::class.java))
        finish()
    }

    companion object {
        private const val TAG = "RingActivity"

        fun start(context: Context, ringing: RingingAlarm) {
            val intent = Intent(context, RingActivity::class.java).apply {
                ringing.writeTo(this)
            }
            ContextCompat.startActivity(context, intent, null)
        }
    }
}
```

- [ ] **Step 3: 编译确认通过**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 跑全量测试确认无回归**

```powershell
.\gradlew.bat test --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/ui/ring/
git commit -m "feat(ui): 新增全屏响铃界面与降级播放路径"
```

---

### Task 13: AlarmReceiver、SystemEventReceiver 与完整 Manifest

**Files:**
- Modify: `app/src/main/java/com/example/clock/alarm/AlarmReceiver.kt`（替换 Task 7 的占位实现）
- Create: `app/src/main/java/com/example/clock/alarm/SystemEventReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/example/clock/alarm/AlarmReceiverTest.kt`
- Test: `app/src/test/java/com/example/clock/alarm/SystemEventReceiverTest.kt`

**Interfaces:**
- Consumes: `AlarmScheduler`（Task 7）、`RingAudioController`（Task 8）、`RingingAlarm`（Task 9）、`RingService`（Task 11）、`RingActivity`（Task 12）、`AlarmRepository`（Task 6）
- Produces: 完整的响铃链路。`AlarmReceiver` 处理 `ACTION_FIRE_ALARM`。

- [ ] **Step 1: 写 AlarmReceiver 的失败测试**

`app/src/test/java/com/example/clock/alarm/AlarmReceiverTest.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.clock.AppGraph
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var repository: AlarmRepository

    private fun fireIntent(ringing: RingingAlarm, isSnooze: Boolean = false): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
            ringing.writeTo(this)
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, isSnooze)
        }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // AppGraph 与 AlarmDatabase 都是进程级单例，Robolectric 不会替我们重置。
        AppGraph.resetForTesting()
        repository = AppGraph.repository(context)
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    /** 建一个闹钟并返回它的 id。 */
    private fun seedAlarm(mask: Int = RepeatDays.NONE_MASK): Long = runBlocking {
        repository.save(Alarm(hour = 7, minute = 0, label = "起床", repeatDays = RepeatDays(mask)))
    }

    @Test
    fun `一次性闹钟响铃后自动关闭`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val after = runBlocking { repository.getById(id) }
        assertNotNull(after)
        assertFalse("一次性闹钟响完必须自己关掉", after!!.enabled)
    }

    @Test
    fun `重复闹钟响铃后保持启用`() {
        val id = seedAlarm(RepeatDays.WEEKDAYS_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        assertTrue("重复闹钟不应被关闭", runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `贪睡触发的响铃不再关闭任何闹钟`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true), isSnooze = true))

        assertTrue(
            "贪睡响铃不应改动闹钟",
            runBlocking { repository.getById(id) }!!.enabled,
        )
    }

    @Test
    fun `非法 Intent 时不做任何事也不崩溃`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)
        val bad = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_FIRE_ALARM
        }

        AlarmReceiver().onReceive(context, bad)

        assertTrue(runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `action 不匹配时直接返回`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)
        val wrong = fireIntent(RingingAlarm(id, 7, 0, "x", true)).apply {
            action = "com.example.clock.SOMETHING_ELSE"
        }

        AlarmReceiver().onReceive(context, wrong)

        assertTrue(runBlocking { repository.getById(id) }!!.enabled)
    }

    @Test
    fun `响铃后会重新注册重复闹钟的下一次`() {
        val id = seedAlarm(RepeatDays.EVERY_DAY_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assertNotNull(
            "响铃后应当仍有一个待触发的闹钟",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `一次性闹钟响铃后不再有待触发的闹钟`() {
        val id = seedAlarm(RepeatDays.NONE_MASK)

        AlarmReceiver().onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assertTrue(
            "一次性闹钟关闭后不应还留着注册",
            shadowOf(alarmManager).peekNextScheduledAlarm() == null,
        )
    }

    @Test
    fun `前台服务启动失败时仍然完成关闭一次性闹钟`() {
        // Review Focus 第 5 条：降级路径不能中断后续的数据库善后。
        val id = seedAlarm(RepeatDays.NONE_MASK)

        val receiver = AlarmReceiver().apply {
            serviceStarter = { _, _ -> error("模拟前台服务启动被系统拒绝") }
        }
        receiver.onReceive(context, fireIntent(RingingAlarm(id, 7, 0, "起床", true)))

        assertFalse(
            "前台服务失败不应中断关闭一次性闹钟",
            runBlocking { repository.getById(id) }!!.enabled,
        )
    }
}
```

- [ ] **Step 2: 写 SystemEventReceiver 的失败测试**

`app/src/test/java/com/example/clock/alarm/SystemEventReceiverTest.kt`

```kotlin
package com.example.clock.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.clock.AppGraph
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class SystemEventReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTesting()
        runBlocking {
            AppGraph.repository(context).save(
                Alarm(hour = 7, minute = 0, repeatDays = RepeatDays(RepeatDays.EVERY_DAY_MASK)),
            )
        }
        // 静态方法，不能经由 shadowOf(...) 的实例调用。
        ShadowAlarmManager.setAutoSchedule(false)
    }

    private fun hasScheduledAlarm(): Boolean =
        shadowOf(context.getSystemService(AlarmManager::class.java))
            .peekNextScheduledAlarm() != null

    /** Review Focus 第 3 条：这四种系统事件任何一个漏接，闹钟都会静默失效。 */
    private val reschedulingActions = listOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    @Test
    fun `全部系统事件都会触发重算`() {
        for (action in reschedulingActions) {
            ShadowAlarmManager.setAutoSchedule(false)

            SystemEventReceiver().onReceive(context, Intent(action))

            assertTrue("$action 应当触发重算注册", hasScheduledAlarm())
        }
    }

    @Test
    fun `无关的 action 不触发重算`() {
        SystemEventReceiver().onReceive(context, Intent("com.example.clock.IRRELEVANT"))
        assertTrue(
            "无关广播不应注册闹钟",
            !hasScheduledAlarm(),
        )
    }

    @Test
    fun `null action 不崩溃`() {
        SystemEventReceiver().onReceive(context, Intent())
        assertTrue(!hasScheduledAlarm())
    }
}
```

- [ ] **Step 3: 说明本任务不需要额外写测试接缝**

两个 Receiver 都通过 `AppGraph` 取依赖，测试只需在 `@Before` 里调用 `AppGraph.resetForTesting()` 就能拿到干净的、绑定当前测试 Context 的实例 —— 这条接缝在 Task 6 和 Task 7 已经建好了。

**唯一需要新增的接缝是"让前台服务启动失败"**，它做在 `AlarmReceiver` 的实例上：把这个函数体的第 2 步写成调用一个 `@VisibleForTesting internal var serviceStarter: (Context, RingingAlarm) -> Unit`（默认值 `RingService::start`）。测试直接替换这个字段即可，**不要**引入全局可变的 `TestHooks` object —— 那种全局状态会在测试之间泄漏，最后变成一个新的 bug 来源。

- [ ] **Step 4: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.AlarmReceiverTest" --tests "com.example.clock.alarm.SystemEventReceiverTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'SystemEventReceiver'`（`AlarmReceiver` 此刻还是空占位，相关断言会失败）。

- [ ] **Step 5: 写 AlarmReceiver**

`app/src/main/java/com/example/clock/alarm/AlarmReceiver.kt`

```kotlin
package com.example.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.clock.AppGraph
import com.example.clock.ui.ring.RingActivity
import kotlinx.coroutines.runBlocking

/**
 * 闹钟到点时的入口。
 *
 * 执行顺序是刻意安排的：
 * 1. 先拉起全屏界面 —— 这是唯一不受后台限制的一步，失败概率最低，也就最该先做
 * 2. 再尝试启动前台服务，失败只降级不中断（见 spec 5.6）
 * 3. 最后做数据库善后：关闭一次性闹钟、给重复闹钟排下一次
 */
class AlarmReceiver : BroadcastReceiver() {

    /**
     * 前台服务启动的注入点。测试把它换成会抛异常的 lambda，就能验证
     * "服务起不来时流程仍然走完"这条降级路径 —— 这条路径在真机上几乎
     * 无法复现（见 spec 5.6 与 Google Issue Tracker #238340795）。
     *
     * 用实例字段而不是全局 object：Receiver 每次广播都会新建实例，
     * 但测试里是直接 new 一个来调用的，所以替换字段即可，且不会在
     * 测试之间泄漏状态。
     */
    @VisibleForTesting
    internal var serviceStarter: (Context, RingingAlarm) -> Unit = RingService::start

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE_ALARM) return

        val ringing = RingingAlarm.from(intent)
        if (ringing == null) {
            Log.w(TAG, "收到的响铃 Intent 数据不完整，忽略：$intent")
            return
        }

        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)

        // 1. 先把界面拉起来。多余的耗时操作都排在它后面。
        try {
            RingActivity.start(context, ringing)
        } catch (e: Exception) {
            // 后台启动 Activity 在个别 ROM 上会被拒绝；此时通知里的 fullScreenIntent
            // 和前台服务仍然是可用的，所以不中断流程。
            Log.w(TAG, "启动响铃界面失败，依赖通知继续响铃", e)
        }

        // 2. 尝试前台服务。失败时铃声由 RingActivity 播放 —— RingAudioController
        //    是幂等的，两条路径不会叠音。
        try {
            serviceStarter(context, ringing)
        } catch (e: Exception) {
            Log.w(TAG, "前台服务启动失败，降级为由响铃界面播放铃声", e)
        }

        // 3. 数据库善后。用 runBlocking 而非 goAsync()+协程：这里只有一两次极小的
        //    事务，同步做完可以让"响铃后必然完成重算"这件事变得无需推理。
        if (!isSnooze) {
            runBlocking {
                try {
                    val repository = AppGraph.repository(context)
                    val alarm = repository.getById(ringing.id)

                    if (alarm != null && alarm.repeatDays.isOnceOnly) {
                        // 一次性闹钟响完必须自己关掉，否则明天同一时间还会响。
                        repository.disableSilently(ringing.id)
                    }
                    AppGraph.scheduler(context).scheduleAll()
                } catch (e: Exception) {
                    Log.e(TAG, "响铃后的重算失败", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
```

> **关于 `alarm/` 依赖 `ui/`**：`AlarmReceiver` 直接 import 了 `RingActivity`，这违反了全局约束里"`alarm/` 不得 import `ui/`"的规则。这是**唯一一处刻意的例外**，理由是：启动 Activity 必须用显式 Intent 才能保证在 `AlarmManager` 唤醒的瞬间不被隐式 Intent 解析延迟拖慢，而响铃延迟是闹钟 app 的核心体验指标。请在代码里加一行注释说明这个例外，不要试图用反射或 action 匹配去"修正"它 —— 那只会引入新的失败点。

- [ ] **Step 6: 写 SystemEventReceiver**

`app/src/main/java/com/example/clock/alarm/SystemEventReceiver.kt`

```kotlin
package com.example.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.clock.AppGraph
import kotlinx.coroutines.runBlocking

/**
 * 系统事件触发重算。
 *
 * **四种事件一个都不能少**：任何一个漏接，闹钟都会静默失效且没有任何报错 ——
 * 这是闹钟 app 最难排查的故障模式。
 *
 * manifest 中声明 android:exported="false"。按 <receiver> 的官方文档，
 * exported="false" 的接收器仍能收到系统广播（只是收不到普通应用发来的），
 * 而这四个广播全都是系统发出的受保护广播。
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> reschedule(context)

            else -> Unit
        }
    }

    private fun reschedule(context: Context) {
        runBlocking {
            try {
                AppGraph.scheduler(context).scheduleAll()
            } catch (e: Exception) {
                Log.e(TAG, "系统事件后重算闹钟失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"
    }
}
```

- [ ] **Step 7: 写完整的 AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 精确闹钟。
         33+ 用 USE_EXACT_ALARM：闹钟类应用由系统自动授予，无需引导用户去设置页。
         32 及以下用 SCHEDULE_EXACT_ALARM，在 Android 12/13 上同样是自动授予的。 -->
    <uses-permission
        android:name="android.permission.SCHEDULE_EXACT_ALARM"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 响铃界面。showWhenLocked/turnScreenOn 让它无需解锁即可显示；
             singleTask 保证重复触发（直接启动 + 通知的 fullScreenIntent）只
             产生一个实例，第二次走 onNewIntent。 -->
        <activity
            android:name=".ui.ring.RingActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleTask"
            android:showOnLockScreen="true"
            android:showWhenLocked="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Material.NoActionBar"
            android:turnScreenOn="true">
            <intent-filter>
                <action android:name="com.example.clock.action.SHOW_RING_UI" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <service
            android:name=".alarm.RingService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />

        <receiver
            android:name=".alarm.AlarmReceiver"
            android:exported="false" />

        <!-- exported="false" 仍能收到系统广播。这四种广播都是系统受保护广播，
             普通应用无法伪造，因此不需要 exported="true"。 -->
        <receiver
            android:name=".alarm.SystemEventReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
    </application>

</manifest>
```

> `android:showOnLockScreen` 与 `android:showWhenLocked` 都写上：前者被部分旧 ROM 使用，后者是官方属性，两者并存无害。

- [ ] **Step 8: 补上 launcher 图标资源**

若 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` 不存在，创建它以及最小自适应图标资源：

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1A1A2E</color>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,30 A24,24 0 1,0 54,78 A24,24 0 1,0 54,30 Z M54,36 A18,18 0 1,1 54,72 A18,18 0 1,1 54,36 Z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M52,42 L56,42 L56,56 L52,56 Z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,54 L66,60 L64,63 L52,57 Z" />
</vector>
```

- [ ] **Step 9: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.alarm.AlarmReceiverTest" --tests "com.example.clock.alarm.SystemEventReceiverTest" --no-daemon
```

预期：全部 PASS。

- [ ] **Step 10: 跑全量测试与构建**

```powershell
.\gradlew.bat test assembleDebug --no-daemon
```

预期：全部 PASS，`BUILD SUCCESSFUL`。

- [ ] **Step 11: 人工代码审查确认降级路径**

**这一步没有自动化测试可跑**，必须人工读代码确认（Review Focus 第 5 条）：

1. 打开 `AlarmReceiver.onReceive`，确认 `RingService.start(...)` 确实被 `try/catch` 包住，且 catch 分支之后**没有** return —— 第 3 步的数据库善后必须照常执行。
2. 打开 `RingActivity.onStart`，确认它调用了 `RingAudioController.start()`。
3. 打开 `RingAudioController.start`，确认第一行就是 `if (ringing) return`。
4. 确认 `MediaPlayerAlarmPlayer.start()` 的第一件事是 `releasePlayer()`。

四条都对，才说明"服务失败时 Activity 播放、两条路径不叠音"这个链条成立。

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/example/clock/alarm/ app/src/main/java/com/example/clock/ui/ring/ app/src/main/res/ app/src/main/AndroidManifest.xml app/src/test/java/com/example/clock/alarm/
git commit -m "feat(alarm): 打通响铃链路与系统事件重算，补全权限声明"
```

---

### Task 14: MainActivity、导航与通知权限

**Files:**
- Modify: `app/src/main/java/com/example/clock/ui/MainActivity.kt`
- Create: `app/src/main/java/com/example/clock/ui/ClockNavHost.kt`
- Create: `app/src/main/java/com/example/clock/ui/NotificationPermissionGate.kt`
- Test: `app/src/test/java/com/example/clock/ui/NotificationPermissionTest.kt`

**Interfaces:**
- Consumes: `ClockTheme`（Task 1）、`AlarmListScreen`（Task 15）、`AlarmEditScreen`（Task 16）
- Produces: `@Composable fun ClockNavHost()`、`@Composable fun NotificationPermissionGate()`

> 本任务创建导航骨架并引用了 Task 15、16 才写的两个界面。为了让本任务能独立编译提交，**Step 2 先创建这两个界面的最小占位**（只显示一行文字），Task 15、16 再替换成真正的实现。

- [ ] **Step 1: 写通知权限决策的失败测试**

把"是否需要请求通知权限"的判断抽成纯函数，就能在 JVM 上测试。

`app/src/test/java/com/example/clock/ui/NotificationPermissionTest.kt`

```kotlin
package com.example.clock.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {

    @Test
    fun `Android 12 及以下不需要运行时请求`() {
        for (sdk in 26..32) {
            assertFalse(
                "sdk=$sdk 不应请求通知权限",
                shouldRequestNotificationPermission(sdkInt = sdk, hasPermission = false, alreadyAsked = false),
            )
        }
    }

    @Test
    fun `Android 13 且未授权且未问过 才需要请求`() {
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = false, alreadyAsked = false),
        )
    }

    @Test
    fun `Android 13 已授权则不请求`() {
        assertFalse(
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = true, alreadyAsked = false),
        )
    }

    @Test
    fun `已经问过就不再重复弹窗`() {
        assertFalse(
            "用户拒绝后不应反复骚扰",
            shouldRequestNotificationPermission(sdkInt = 33, hasPermission = false, alreadyAsked = true),
        )
    }

    @Test
    fun `Android 14 及以上同样需要请求`() {
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 34, hasPermission = false, alreadyAsked = false),
        )
        assertTrue(
            shouldRequestNotificationPermission(sdkInt = 35, hasPermission = false, alreadyAsked = false),
        )
    }
}
```

- [ ] **Step 2: 创建两个界面的最小占位**

`app/src/main/java/com/example/clock/ui/list/AlarmListScreen.kt`（占位，Task 15 替换）

```kotlin
package com.example.clock.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AlarmListScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Task 15 替换为真正的列表
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("闹钟列表")
    }
}
```

`app/src/main/java/com/example/clock/ui/edit/AlarmEditScreen.kt`（占位，Task 16 替换）

```kotlin
package com.example.clock.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AlarmEditScreen(
    alarmId: Long?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Task 16 替换为真正的编辑界面
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("编辑闹钟 $alarmId")
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.ui.NotificationPermissionTest" --no-daemon
```

预期：编译失败，`Unresolved reference 'shouldRequestNotificationPermission'`。

- [ ] **Step 4: 写通知权限模块**

`app/src/main/java/com/example/clock/ui/NotificationPermissionGate.kt`

```kotlin
package com.example.clock.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 是否需要请求通知权限。
 *
 * POST_NOTIFICATIONS 是 Android 13 引入的运行时权限，也是本 app 在目标设备
 * （Android 12/13）上唯一需要运行时请求的权限 —— 精确闹钟权限在 12/13 上是
 * 系统自动授予的。
 */
fun shouldRequestNotificationPermission(
    sdkInt: Int,
    hasPermission: Boolean,
    alreadyAsked: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !hasPermission && !alreadyAsked

fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/**
 * 首次进入时请求一次通知权限。
 *
 * 用户拒绝后**不再重复弹窗** —— 反复骚扰只会让人直接卸载。改为提示一次影响：
 * 闹钟仍会响（由全屏界面负责），只是通知栏上的快捷关闭入口不可见。
 *
 * 这是 Review Focus 第 4 条的落点：拒绝权限的路径必须有明确的用户可见反馈，
 * 否则用户遇到"铃声没有关闭入口"时会完全不知道为什么。
 */
@Composable
fun NotificationPermissionGate() {
    val context = LocalContext.current
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }
    var showDeniedNotice by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showDeniedNotice = true
    }

    LaunchedEffect(Unit) {
        if (shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                hasPermission = hasNotificationPermission(context),
                alreadyAsked = alreadyAsked,
            )
        ) {
            alreadyAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showDeniedNotice) {
        AlertDialog(
            onDismissRequest = { showDeniedNotice = false },
            title = { Text("通知权限未开启") },
            text = {
                Text(
                    "闹钟到点仍会全屏响铃，但通知栏上不会显示，也就少了一个快速关闭的入口。" +
                        "如果想让闹钟更可靠，可以到「设置 → 应用 → 闹钟 → 通知」里手动开启。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeniedNotice = false }) { Text("知道了") }
            },
        )
    }
}
```

- [ ] **Step 5: 写导航**

`app/src/main/java/com/example/clock/ui/ClockNavHost.kt`

```kotlin
package com.example.clock.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.clock.ui.edit.AlarmEditScreen
import com.example.clock.ui.list.AlarmListScreen

private const val ROUTE_LIST = "list"
private const val ROUTE_EDIT = "edit"
private const val ARG_ALARM_ID = "alarmId"

/** 新建闹钟时用的哨兵值：Room 的自增 id 从 1 开始，0 永远不会是真 id。 */
const val NEW_ALARM_ID = 0L

@Composable
fun ClockNavHost() {
    val navController = rememberNavController()

    NotificationPermissionGate()

    NavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            AlarmListScreen(
                onAddAlarm = { navController.navigate("$ROUTE_EDIT/$NEW_ALARM_ID") },
                onEditAlarm = { id -> navController.navigate("$ROUTE_EDIT/$id") },
            )
        }

        composable(
            route = "$ROUTE_EDIT/{$ARG_ALARM_ID}",
            arguments = listOf(navArgument(ARG_ALARM_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val rawId = backStackEntry.arguments?.getLong(ARG_ALARM_ID) ?: NEW_ALARM_ID
            AlarmEditScreen(
                alarmId = rawId.takeIf { it != NEW_ALARM_ID },
                onDone = { navController.popBackStack() },
            )
        }
    }
}
```

- [ ] **Step 6: 替换 MainActivity**

`app/src/main/java/com/example/clock/ui/MainActivity.kt`

```kotlin
package com.example.clock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.clock.ui.theme.ClockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClockTheme {
                ClockNavHost()
            }
        }
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.ui.NotificationPermissionTest" --no-daemon
```

预期：5 个测试 PASS。

- [ ] **Step 8: 构建确认通过**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/clock/ui/
git commit -m "feat(ui): 新增导航骨架与通知权限请求"
```

---

### Task 15: 闹钟列表界面

**Files:**
- Create: `app/src/main/java/com/example/clock/ui/list/AlarmListViewModel.kt`
- Modify: `app/src/main/java/com/example/clock/ui/list/AlarmListScreen.kt`（替换 Task 14 的占位）
- Test: `app/src/test/java/com/example/clock/ui/list/AlarmListSummaryTest.kt`

**Interfaces:**
- Consumes: `AlarmRepository`（Task 6）、`NextTrigger.nextOf`/`formatRemaining`（Task 4）、`Alarm`、`RepeatDays.summary()`（Task 2）
- Produces:
  - `class AlarmListViewModel(repository: AlarmRepository) : ViewModel`，属性 `uiState: StateFlow<AlarmListUiState>`，方法 `setEnabled(id, enabled)`、`delete(id)`、`restore(alarm)`
  - `data class AlarmListUiState(val alarms: List<Alarm>, val nextSummary: String?)`（`nextSummary` 为 null 表示列表为空；列表非空但没有启用闹钟时是「没有启用的闹钟」）
  - `@Composable fun AlarmListScreen(onAddAlarm: () -> Unit, onEditAlarm: (Long) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: 写 ViewModel**

`app/src/main/java/com/example/clock/ui/list/AlarmListViewModel.kt`

```kotlin
package com.example.clock.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.clock.AppGraph
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.NextTrigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

data class AlarmListUiState(
    val alarms: List<Alarm> = emptyList(),
    val nextSummary: String? = null,
)

class AlarmListViewModel(private val repository: AlarmRepository) : ViewModel() {

    val uiState: StateFlow<AlarmListUiState> = repository.observeAll()
        .map { alarms -> AlarmListUiState(alarms, buildNextSummary(alarms)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlarmListUiState(),
        )

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * 撤销删除。
     *
     * 能这么做的前提是 [AlarmRepository.save] 在 id 不存在时会按**原 id** 重新插入
     * （见 Task 6）。如果 save 只会 insert 出新 id，"撤销"就会把闹钟变成一条新记录，
     * 而旧 id 对应的 AlarmManager 注册则由 delete 时的 cancel 清掉了 —— 结果就是
     * 撤销后闹钟看着回来了却再也不会响。
     */
    fun restore(alarm: Alarm) {
        viewModelScope.launch { repository.save(alarm) }
    }

    companion object {
        fun factory(repository: AlarmRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlarmListViewModel(repository) }
        }
    }
}

/**
 * 摘要行的文案。三个分支各是一条明确的契约（见本任务 Interfaces）：
 * 列表为空 -> null（整页走空状态）；列表非空但没有启用的闹钟 -> 「没有启用的闹钟」；
 * 否则 -> 「下一个闹钟：周X HH:MM（剩余…）」。
 *
 * 刻意放在**文件顶层**而不是 ViewModel 的成员：它是纯函数，不需要 `this`，放在顶层就能被
 * 一个不构造 ViewModel、不碰协程的普通 JVM 测试直接调用（见 Step 2）。
 *
 * `now` 可注入，默认取当前时刻；生产调用点不传，测试传固定值以获得确定性 ——
 * 否则第三个分支的期望值会随墙上时钟漂移，这个测试根本没法写。
 *
 * 注意 [NextTrigger.nextOf] 自己就会过滤掉未启用的闹钟（Task 4），所以这里必须传
 * **未过滤**的完整列表；正因如此「没有启用的闹钟」这个分支是靠 nextOf 返回 null 命中的。
 */
internal fun buildNextSummary(
    alarms: List<Alarm>,
    now: ZonedDateTime = ZonedDateTime.now(),
): String? {
    // 列表为空时整页走空状态，不显示摘要行。
    if (alarms.isEmpty()) return null
    val next = NextTrigger.nextOf(alarms, now) ?: return "没有启用的闹钟"
    val weekday = weekdayName(next.at.dayOfWeek.value)
    val time = "%02d:%02d".format(next.at.hour, next.at.minute)
    return "下一个闹钟：$weekday $time（${NextTrigger.formatRemaining(now, next.at)}）"
}

private fun weekdayName(value: Int): String = when (value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}
```

- [ ] **Step 2: 写摘要逻辑的测试**

`app/src/test/java/com/example/clock/ui/list/AlarmListSummaryTest.kt`

摘要行是本页最显眼的一行文案，而它有三个分支，且第三个分支依赖一条**跨任务契约**：
`NextTrigger.nextOf` 会自己过滤未启用的闹钟（Task 4），所以「列表非空但一个都没启用」
必须落到「没有启用的闹钟」而不是 null。

因为 `buildNextSummary` 是顶层 `internal` 纯函数、`now` 又可注入，这是一个**纯 JVM 测试**：
不需要 Robolectric，不需要协程测试库，也不需要构造 `AlarmListViewModel`。

```kotlin
package com.example.clock.ui.list

import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AlarmListSummaryTest {

    // 固定为周一 22:00（+08:00），让"下一个"的期望值完全确定。
    private val now = ZonedDateTime.parse("2026-10-13T22:00:00+08:00")

    private fun summaryOf(vararg alarms: Alarm) = buildNextSummary(alarms.toList(), now)

    @Test
    fun `列表为空时没有摘要`() {
        assertNull(summaryOf())
    }

    @Test
    fun `有闹钟但一个都没启用时提示没有启用的闹钟`() {
        val disabled = Alarm(
            id = 1L, hour = 7, minute = 0,
            repeatDays = RepeatDays.EVERY_DAY, enabled = false,
        )
        assertEquals("没有启用的闹钟", summaryOf(disabled))
    }

    @Test
    fun `有启用的闹钟时给出下一个的时刻与剩余时间`() {
        // now 是周一 22:00，工作日 7:00 的下一次落在周二。
        val enabled = Alarm(id = 1L, hour = 7, minute = 0, repeatDays = RepeatDays.WEEKDAYS)
        assertTrue(summaryOf(enabled)!!.startsWith("下一个闹钟：周二 07:00（"))
    }

    @Test
    fun `未启用的闹钟不参与计算`() {
        val disabled = Alarm(
            id = 1L, hour = 7, minute = 0,
            repeatDays = RepeatDays.EVERY_DAY, enabled = false,
        )
        val enabled = Alarm(id = 2L, hour = 9, minute = 30, repeatDays = RepeatDays.EVERY_DAY)
        assertTrue(summaryOf(disabled, enabled)!!.contains("09:30"))
    }
}
```

写完后先单独跑一次确认是绿的。注意这个测试钉的是 Step 1 里已经写好的代码，所以**没有 RED 阶段**
（它是补钉既有行为的测试，不是 TDD 的第一枪）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.clock.ui.list.AlarmListSummaryTest" --no-daemon
```

预期：4 个测试 PASS。

- [ ] **Step 3: 写界面**

`app/src/main/java/com/example/clock/ui/list/AlarmListScreen.kt`（整体替换）

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.clock.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clock.AppGraph
import com.example.clock.domain.Alarm
import kotlinx.coroutines.launch

@Composable
fun AlarmListScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: AlarmListViewModel = viewModel(
        factory = AlarmListViewModel.factory(AppGraph.repository(context)),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 删除是立即生效的，撤销靠 Snackbar。之所以敢这么做，是因为 save() 能按原 id
    // 复原（见 AlarmListViewModel.restore 的注释）。
    val deleteWithUndo: (Alarm) -> Unit = { alarm ->
        viewModel.delete(alarm.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已删除「${alarm.displayName()}」",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restore(alarm)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("闹钟") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAlarm) {
                Icon(Icons.Filled.Add, contentDescription = "添加闹钟")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 列表为空时整页走空状态，这一行不显示。
            uiState.nextSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (uiState.alarms.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.alarms, key = { it.id }) { alarm ->
                        SwipeToDeleteRow(onDelete = { deleteWithUndo(alarm) }) {
                            AlarmCard(
                                alarm = alarm,
                                onClick = { onEditAlarm(alarm.id) },
                                onToggle = { enabled -> viewModel.setEnabled(alarm.id, enabled) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "还没有闹钟，点右下角添加",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 左滑（从右往左）触发删除。反向滑动被禁用，避免和系统的返回手势打架。
 *
 * 删除后卡片会从列表里消失，所以不需要在处理完后重置 [state] —— 整个
 * SwipeToDismissBox 会随 item 一起被移除；撤销恢复时按 key 重建的是全新状态。
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 已关闭的闹钟整体淡化，一眼能区分
            .alpha(if (alarm.enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSubtitle(alarm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 删除提示里用的名字：有标签用标签，没有就用时间。 */
private fun Alarm.displayName(): String =
    label.ifBlank { "%02d:%02d".format(hour, minute) }

private fun buildSubtitle(alarm: Alarm): String =
    if (alarm.label.isBlank()) alarm.repeatDays.summary()
    else "${alarm.label} · ${alarm.repeatDays.summary()}"
```

- [ ] **Step 4: 构建确认通过**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 跑全量测试确认无回归**

```powershell
.\gradlew.bat test --no-daemon
```

预期：全绿，且其中包含本任务新增的 `AlarmListSummaryTest` 的 4 个测试。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/clock/ui/list/ app/src/test/java/com/example/clock/ui/list/
git commit -m "feat(ui): 新增闹钟列表界面与摘要逻辑测试"
```

---

### Task 16: 编辑界面

**Files:**
- Create: `app/src/main/java/com/example/clock/ui/edit/AlarmEditViewModel.kt`
- Modify: `app/src/main/java/com/example/clock/ui/edit/AlarmEditScreen.kt`（替换 Task 14 的占位）

**Interfaces:**
- Consumes: `AlarmRepository`（Task 6）、`RepeatDays`（Task 2）、`Alarm`（Task 4）
- Produces:
  - `class AlarmEditViewModel(repository, alarmId: Long?) : ViewModel`，属性 `draft: StateFlow<Alarm?>`，方法 `setHour(Int)`、`setMinute(Int)`、`setLabel(String)`、`toggleDay(DayOfWeek)`、`applyPreset(RepeatDays)`、`setVibrate(Boolean)`、`save(onSaved: () -> Unit)`、`delete(onDeleted: () -> Unit)`
  - `@Composable fun AlarmEditScreen(alarmId: Long?, onDone: () -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: 写 ViewModel**

`app/src/main/java/com/example/clock/ui/edit/AlarmEditViewModel.kt`

```kotlin
package com.example.clock.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.clock.data.AlarmRepository
import com.example.clock.domain.Alarm
import com.example.clock.domain.RepeatDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

class AlarmEditViewModel(
    private val repository: AlarmRepository,
    private val alarmId: Long?,
) : ViewModel() {

    private val _draft = MutableStateFlow(defaultDraft())
    val draft: StateFlow<Alarm?> = _draft.asStateFlow()

    init {
        if (alarmId != null) {
            viewModelScope.launch {
                repository.getById(alarmId)?.let { _draft.value = it }
            }
        }
    }

    fun setHour(hour: Int) = update { it.copy(hour = hour.coerceIn(0, 23)) }

    fun setMinute(minute: Int) = update { it.copy(minute = minute.coerceIn(0, 59)) }

    fun setLabel(label: String) = update { it.copy(label = label) }

    fun setVibrate(vibrate: Boolean) = update { it.copy(vibrate = vibrate) }

    fun toggleDay(day: DayOfWeek) = update { it.copy(repeatDays = it.repeatDays.toggle(day)) }

    fun applyPreset(preset: RepeatDays) = update { it.copy(repeatDays = preset) }

    fun save(onSaved: () -> Unit) {
        val alarm = _draft.value ?: return
        viewModelScope.launch {
            repository.save(alarm)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = alarmId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    private fun update(transform: (Alarm) -> Alarm) {
        _draft.value = _draft.value?.let(transform)
    }

    private fun defaultDraft(): Alarm {
        // 新建时默认 7:00、仅一次、开启震动——这是最常见的起床闹钟形态。
        val now = LocalTime.now()
        return Alarm(
            hour = if (alarmId == null) 7 else now.hour,
            minute = if (alarmId == null) 0 else now.minute,
        )
    }

    companion object {
        fun factory(repository: AlarmRepository, alarmId: Long?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { AlarmEditViewModel(repository, alarmId) }
            }
    }
}
```

- [ ] **Step 2: 写界面**

`app/src/main/java/com/example/clock/ui/edit/AlarmEditScreen.kt`（整体替换）

```kotlin
package com.example.clock.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clock.AppGraph
import com.example.clock.domain.RepeatDays
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: AlarmEditViewModel = viewModel(
        key = "edit-${alarmId ?: 0L}",
        factory = AlarmEditViewModel.factory(AppGraph.repository(context), alarmId),
    )
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    val timePickerState = rememberTimePickerState(
        initialHour = draft?.hour ?: 7,
        initialMinute = draft?.minute ?: 0,
        is24Hour = true,
    )

    // 编辑既有闹钟时，数据是异步读出来的，需要把选择器同步过去一次。
    LaunchedEffect(draft?.id) {
        draft?.let {
            timePickerState.hour = it.hour
            timePickerState.minute = it.minute
        }
    }

    // 用户拨动表盘后写回草稿。
    LaunchedEffect(timePickerState.hour, timePickerState.minute) {
        viewModel.setHour(timePickerState.hour)
        viewModel.setMinute(timePickerState.minute)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // spec 6.2 要求表盘与数字输入两种模式可切换。两个 Composable 共用同一个
    // timePickerState，所以来回切换不会丢已经选好的时间。
    var useDial by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == null) "新建闹钟" else "编辑闹钟") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (alarmId != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = draft
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (useDial) {
                TimePicker(state = timePickerState)
            } else {
                TimeInput(state = timePickerState)
            }

            TextButton(
                onClick = { useDial = !useDial },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (useDial) "键盘输入" else "表盘")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "重复",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = current.repeatDays.includes(day),
                        onClick = { viewModel.toggleDay(day) },
                        label = { Text(shortName(day)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = current.repeatDays.mask == RepeatDays.WEEKDAYS_MASK,
                    onClick = { viewModel.applyPreset(RepeatDays.WEEKDAYS) },
                    label = { Text("工作日") },
                )
                FilterChip(
                    selected = current.repeatDays.mask == RepeatDays.EVERY_DAY_MASK,
                    onClick = { viewModel.applyPreset(RepeatDays.EVERY_DAY) },
                    label = { Text("每天") },
                )
                FilterChip(
                    selected = current.repeatDays.isOnceOnly,
                    onClick = { viewModel.applyPreset(RepeatDays.NONE) },
                    label = { Text("仅一次") },
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = current.label,
                onValueChange = viewModel::setLabel,
                label = { Text("标签（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("震动", modifier = Modifier.weight(1f))
                Switch(checked = current.vibrate, onCheckedChange = viewModel::setVibrate)
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.save(onSaved = onDone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("保存")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这个闹钟？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(onDeleted = onDone)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

private fun shortName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
```

- [ ] **Step 3: 构建确认通过**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`。

若 `TimePicker` / `TimeInput` 报实验性 API 错误，确认函数上保留了 `@OptIn(ExperimentalMaterial3Api::class)`。若 `TimeInput` 报 `Unresolved reference`，说明 material3 版本低于预期 —— 检查 `gradle/libs.versions.toml` 里 `composeBom` 是否为 `2024.11.00`。

- [ ] **Step 4: 跑全量测试**

```powershell
.\gradlew.bat test --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clock/ui/edit/
git commit -m "feat(ui): 新增闹钟编辑界面"
```

---

### Task 17: 安装到真机、回归验证与 README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: 前面全部任务的产物
- Produces: 一份可复现的真机回归清单

- [ ] **Step 1: 连接真机并安装**

手机开启「开发者选项 → USB 调试」，USB 连接后：

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
```

预期：列出你的设备（不是 `unauthorized` 或空）。

```powershell
.\gradlew.bat installDebug --no-daemon
```

预期：`BUILD SUCCESSFUL`，手机上出现「闹钟」图标。

- [ ] **Step 2: 厂商 ROM 白名单（必做，否则第 7 条必失败）**

在手机的「设置 → 电池」或「应用管理 → 闹钟」中，把本应用设为**不受限制 / 允许后台运行 / 允许自启动**。小米、华为、OPPO、vivo 的 ROM 尤其必要 —— 这是国内 Android 闹钟类应用的通用坑，不做这一步前台服务会被厂商省电策略杀掉。

- [ ] **Step 3: 写 README.md**

```markdown
# 闹钟

一个 Android 原生闹钟 app（Kotlin + Jetpack Compose + Room）。

## 构建

    .\gradlew.bat assembleDebug

## 架构

    ui/      Compose 界面与 ViewModel
    data/    Room 持久化，对外只暴露 domain.Alarm
    domain/  纯 Kotlin，全部时间计算逻辑，不依赖任何 Android API
    alarm/   Android 系统层：调度、响铃服务、广播接收

两条硬性约束：

1. `domain/` 内不得出现任何 `android.*` 或 `androidx.room.*` 的 import。
   这保证了核心时间计算能在 JVM 上毫秒级测试（`.\gradlew.bat test`）。
2. 数据库里**没有**「下次触发时间」字段。下次触发时间永远由
   `AlarmScheduler.scheduleAll()` 现算现注册。任何持久化的触发时间都可能与
   实际状态不同步，一旦不同步就是「闹钟静默不响」这种最难查的 bug。

## 测试

    .\gradlew.bat test

时间计算、掩码运算、调度注册、铃声幂等性都在本地 JVM 上测（Robolectric），
不需要设备。

## 真机回归清单

改动响铃链路后请逐条验证。**第 7、8 条在模拟器上不可靠，必须真机。**

- [ ] 1. 建一个 2 分钟后的闹钟 → 熄屏 → 到点全屏响铃 + 震动
- [ ] 2. 点「贪睡」→ 10 分钟后再次响铃
- [ ] 3. 点「关闭」→ 铃声停止；若是一次性闹钟，列表中该闹钟开关自动变为关闭
- [ ] 4. 建一个「工作日」重复闹钟 → 改系统日期逐天验证，只在周一~周五响
- [ ] 5. 重启手机 → 闹钟仍然有效
- [ ] 6. 手动修改系统时间（前后各调一次）→ 闹钟重算正确，不静默失效
- [ ] 7. 在最近任务列表里划掉 app → 闹钟仍然响
- [ ] 8. **熄屏静置 30 分钟 → Doze 下仍精确响**（最关键）
- [ ] 9. 响铃中从通知栏点「关闭」→ 与在界面里点关闭行为一致
- [ ] 10. 设备切换时区 → 重复闹钟仍按本地时间正确触发
- [ ] 11. 响铃时**不出现两路铃声重叠**
- [ ] 12. 连续贪睡数次 → 每次铃声都正常开始与停止，无残留
- [ ] 13. 拒绝通知权限后 → 闹钟仍全屏响铃，且界面里有明确提示
- [ ] 14. 手机静音/勿扰模式下 → 记录实际表现（预期仍然响）
- [ ] 15. 列表里左滑删掉一个**已启用的**闹钟 → 弹「已删除…撤销」→ 点撤销 → 闹钟回到原位、开关仍是开启，且到点**仍然会响**（验证 `save()` 保留了原 id，注册也被重新排上）
- [ ] 16. 关掉所有闹钟的开关 → 顶部摘要显示「没有启用的闹钟」

## 已知风险

`startForegroundService()` 在 Android 12+ 上偶发抛
`ForegroundServiceStartNotAllowedException`（Google Issue Tracker #238340795，
实测约 1/2500）。因此响铃链路设计为三级互为备份：全屏 Activity → 前台服务 →
通知，且铃声由幂等的 `RingAudioController` 统一持有，任一环节失败都不会导致
完全不响，也不会叠音。详见 `docs/superpowers/specs/` 下的设计文档第 5.6 节。

## 环境

- JDK 17
- Android SDK：platform-tools、platforms;android-35、build-tools;35.0.0
- `ANDROID_HOME` 指向 SDK 根目录
```

- [ ] **Step 4: 逐条执行回归清单**

按 README 中的 14 条逐条在真机上验证，把不符合预期的条目记下来。

**第 8 条请务必执行**：设一个 30 分钟后的闹钟，熄屏放一边，不要碰手机。这是唯一能验证 Doze 下可靠性的方法，也是整个 app 的核心价值所在。

任何一条不通过，用 `superpowers:systematic-debugging` 排查后修复，并在修复后重新跑一遍完整清单。

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: 新增 README 与真机回归清单"
```

---

## 完成标准

全部任务完成后，以下条件应同时成立：

- `.\gradlew.bat test` 全绿，无需连接设备
- `.\gradlew.bat assembleDebug` 成功
- `app/schemas/` 下的 Room schema 已提交
- README 中的 14 条真机回归清单全部通过（第 14 条可以只记录现象）
- `domain/` 包内 `grep -r "import android" app/src/main/java/com/example/clock/domain/` 无输出
