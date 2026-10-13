# 闹钟 App 设计文档（v1 核心版）

- 日期：2026-10-13
- 目标平台：Android 原生
- 技术栈：Kotlin + Jetpack Compose + Room
- 用途：自用 / 学习练手（不发布应用商店）
- 目标设备：Android 12 / 13

---

## 1. 目标与范围

### 1.1 要达成的结果

一个能在真机日常使用的 Android 闹钟应用：能新建、编辑、删除闹钟，支持按星期重复，到点在锁屏上全屏响铃并震动，可以贪睡或关闭。

### 1.2 成功标准

- 设定闹钟后，在**熄屏、Doze 休眠、app 被划掉**的情况下，到点仍能精确响铃。
- 重启手机、手动改系统时间/时区后，闹钟不会静默失效。
- 一次性闹钟响完后自动关闭，不会第二天再响。
- 核心时间计算逻辑有单元测试覆盖，可在电脑上秒级验证，不需要设备。

### 1.3 明确不做（YAGNI）

第一版**不包含**：桌面小组件、自定义铃声选择、渐进音量、数学题/摇一摇强制关闭、睡眠追踪、云同步/备份、多语言、平板/横屏适配。

这些不在 v1 范围内，后续要加时各自走独立的 spec。

---

## 2. 架构

单模块 Android app（仅 `app/` 一个 Gradle 模块），按依赖方向分层，每层只依赖下层，不允许反向依赖。

```
ui/      ──→ ViewModel ──→ Repository
                              │
data/     Room 实体 + DAO + 映射
                              │
domain/   纯 Kotlin：Alarm、RepeatDays、NextTrigger     ← 不依赖任何 Android API
alarm/    Android 系统层：调度、接收、响铃服务（依赖 domain + data）
```

### 2.1 分层的硬性约束

**`domain/` 包内不得出现任何 `android.*` 或 `androidx.room.*` 的 import。**

这是本设计最重要的架构约束。理由是：闹钟 app 唯一真正容易出错的地方是"下次几点响"的计算，把这块隔离成纯函数后，可以在 JVM 上毫秒级跑单元测试覆盖全部边界情况，不需要开模拟器、不需要连真机。`alarm/` 包的职责仅仅是把这个计算结果翻译成 Android API 调用。

**`alarm/` 包不依赖 `ui/`。** 响铃链路（Receiver → Service → Activity）由系统事件驱动，不由界面驱动。唯一的跨层调用是 `AlarmScheduler` 在闹钟增删改后被 Repository 调用，方向是 `data → alarm` 的单一入口函数。

### 2.2 目录结构

```
app/src/main/java/com/example/clock/
├─ ClockApp.kt                    Application：初始化 Room 单例
├─ domain/
│   ├─ Alarm.kt                   纯数据类
│   ├─ RepeatDays.kt              重复星期值对象（7 位掩码）
│   └─ NextTrigger.kt             ★ 纯函数：计算下次触发时刻
├─ data/
│   ├─ AlarmEntity.kt             Room @Entity
│   ├─ AlarmDao.kt                Room @Dao
│   ├─ AlarmDatabase.kt           Room @Database
│   ├─ AlarmRepository.kt         entity ↔ domain 映射 + CRUD + 触发调度
│   └─ Converters.kt              Room 类型转换器（如需要）
├─ alarm/
│   ├─ AlarmScheduler.kt          ★ 唯一的编排入口：重算并注册所有闹钟
│   ├─ AlarmReceiver.kt           BroadcastReceiver：到点被唤醒
│   ├─ RingService.kt             前台服务：播放铃声 + 震动
│   ├─ SystemEventReceiver.kt     开机/应用更新/改时间/改时区 → 重算
│   ├─ RingAudioController.kt     ★ 铃声播放的单一持有者（服务/Activity 降级共用）
│   └─ SnoozeManager.kt           贪睡临时闹钟
└─ ui/
    ├─ theme/                     Material 3 主题
    ├─ MainActivity.kt            承载导航
    ├─ list/
    │   ├─ AlarmListScreen.kt
    │   └─ AlarmListViewModel.kt
    ├─ edit/
    │   ├─ AlarmEditScreen.kt
    │   └─ AlarmEditViewModel.kt
    └─ ring/
        ├─ RingActivity.kt        全屏响铃 Activity（独立于导航图）
        └─ RingScreen.kt

app/src/test/java/com/example/clock/     ← 本地 JVM 单元测试，不需要设备
├─ NextTriggerTest.kt
└─ RepeatDaysTest.kt
```

### 2.3 构建配置

| 项 | 值 |
|---|---|
| `applicationId` | `com.example.clock` |
| `minSdk` | 26（Android 8.0） |
| `targetSdk` | 35 |
| `compileSdk` | 35 |
| JVM target | 17 |
| 构建脚本 | Kotlin DSL（`.kts`）+ `gradle/libs.versions.toml` version catalog |

**`minSdk = 26` 的理由**：Android 8.0 起 `java.time` API 原生可用（`ZonedDateTime`、`LocalTime`、`DayOfWeek`），不需要引入 desugaring 库。`NextTrigger` 的实现强依赖这套 API。

**`applicationId` 说明**：`com.example.*` 是自用侧载场景下的临时值。若将来要上架 Google Play，必须换成自有域名反写的包名（否则会被拒审）——届时应作为独立改动处理。

---

## 3. 数据模型

### 3.1 领域模型

```kotlin
// domain/Alarm.kt —— 纯 Kotlin，无任何 Android / Room 依赖
data class Alarm(
    val id: Long = 0,            // 0 表示尚未入库的新闹钟
    val hour: Int,               // 0..23
    val minute: Int,             // 0..59
    val label: String,           // 可为空字符串
    val repeatDays: RepeatDays,
    val enabled: Boolean,
    val vibrate: Boolean,
)
```

### 3.2 RepeatDays —— 重复星期值对象

用 7 位掩码存储，避免 `Set<DayOfWeek>` 在 Room 中需要 TypeConverter 的麻烦。

```kotlin
// domain/RepeatDays.kt
@JvmInline
value class RepeatDays(val mask: Int) {
    /** mask == 0 表示"仅一次" */
    val isOnceOnly: Boolean get() = mask == 0
    fun includes(day: DayOfWeek): Boolean
    fun summary(): String          // 用于列表页展示
    companion object {
        val NONE: RepeatDays        // 0x00，仅一次
        val EVERY_DAY: RepeatDays   // 0x7F，每天
        val WEEKDAYS: RepeatDays    // 周一至周五
        val WEEKENDS: RepeatDays    // 周六、周日
    }
}
```

**位序约定**：bit0 = 周一，bit1 = 周二，… bit6 = 周日。对应 `java.time.DayOfWeek.value - 1`（`DayOfWeek.MONDAY.value == 1`）。这个映射必须在 `RepeatDaysTest` 中显式断言，避免日后重构时静默错位。

**`mask == 0` 表示"仅一次"** —— 这是一个刻意的约定，全项目统一。一次性闹钟触发后由 `AlarmReceiver` 自动置 `enabled = false`，否则会天天响。

`summary()` 的输出约定：
- `mask == 0` → `"仅一次"`
- `mask == 0x7F` → `"每天"`
- `mask == 0x1F`（周一~周五）→ `"周一至周五"`
- `mask == 0x60`（周六、周日）→ `"周末"`
- 其余 → 按 `"周一、周三、周五"` 格式拼接

### 3.3 Room 实体与数据库

```kotlin
// data/AlarmEntity.kt
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    val repeatMask: Int,
    val enabled: Boolean,
    val vibrate: Boolean,
)
```

数据库 version 1，`exportSchema = true`（schema JSON 提交进版本库，便于日后写迁移）。

`AlarmDao` 提供：
- `@Query("SELECT * FROM alarms ORDER BY hour, minute") fun observeAll(): Flow<List<AlarmEntity>>` —— 列表页订阅
- `@Query("SELECT * FROM alarms WHERE enabled = 1") suspend fun getEnabled(): List<AlarmEntity>` —— 调度器读取
- `@Query("SELECT * FROM alarms WHERE id = :id") suspend fun getById(id: Long): AlarmEntity?`
- `@Insert` / `@Update` / `@Delete`

`AlarmRepository` 负责 `AlarmEntity ↔ Alarm` 双向映射，对外只暴露 `Alarm` 类型。UI 和 alarm 层都看不到 `AlarmEntity`。

### 3.4 关键取舍：不存储"下次触发时间"

**数据库中没有 `nextTriggerTime` 字段。**

下次触发时间永远由 `AlarmScheduler` 在需要时现算现注册。理由是：任何持久化的触发时间都可能与实际状态不同步（用户改了系统时间、时区变了、设备休眠期间被系统清理了 AlarmManager 注册），一旦不同步就会出现"闹钟静默不响"——这是闹钟 app 最严重、也最难排查的故障模式。现算现注册的代价只是每次几毫秒的计算，换来的是不会有陈旧状态。

---

## 4. 核心逻辑：NextTrigger

整个 app 最容易出 bug、也最值得测的一块。

```kotlin
// domain/NextTrigger.kt
object NextTrigger {
    /**
     * 计算严格晚于 [now] 的下一次触发时刻，使用 [now] 自带的时区。
     *
     * @param hour   目标小时 0..23
     * @param minute 目标分钟 0..59
     * @param mask   重复星期掩码，0 表示仅一次
     * @param now    当前时刻（含秒与纳秒，用于"恰好在点上"的判定）
     */
    fun next(hour: Int, minute: Int, mask: Int, now: ZonedDateTime): ZonedDateTime
}
```

### 4.1 算法规则

1. 候选日从**今天**开始，向后最多搜索 7 天。
2. 对每个候选日，构造该日的 `hour:minute` 时刻。
3. 若该时刻**严格晚于** `now`，且（`mask == 0` 或该日的星期命中掩码），即返回。
4. 若 `mask == 0`（仅一次），只在今天和明天两天内判断，不继续向后找。
5. 重复闹钟若今天命中但已过点，跳到下一个命中的星期。

**"严格大于"是硬性要求。** 若 `now` 恰好等于目标时刻（秒与纳秒均为 0），必须返回下一个周期而不是当前时刻。违反这条会导致闹钟立刻再次触发，形成无限循环。

**搜索上界 7 天足以保证有解**：掩码非 0 时，7 天内必然至少命中一天。

### 4.2 必须覆盖的边界场景

以下每条都要有对应的单元测试：

| # | 场景 | 期望结果 |
|---|---|---|
| 1 | 07:00 的闹钟，now = 06:59 | 今天 07:00 |
| 2 | 07:00 的一次性闹钟，now = 07:01 | 明天 07:00 |
| 3 | 07:00 的重复闹钟（周一三五），now = 周一 07:01 | 周三 07:00 |
| 4 | 07:00 的闹钟，now = **07:00:00.000 整** | 下一个周期（不是"现在"） |
| 5 | 07:00 的重复闹钟，now = 周二 08:00（掩码 = 周一三五） | 周三 07:00 |
| 6 | 07:00 的重复闹钟（每天），now = 23:59 | 明天 07:00 |
| 7 | 07:00 的重复闹钟（仅周日），now = 周日 08:00 | 下周日 07:00 |
| 8 | 00:30 的一次性闹钟，now = 1月31日 23:00 | 2月1日 00:30（跨月） |
| 9 | 07:00 的重复闹钟（周末），now = 周五 | 周六 07:00 |
| 10 | 07:00 的重复闹钟（仅周一），now = 周一 06:00 | 今天（当天未过点）07:00 |
| 11 | 23:59 的闹钟，now = 23:59:30 | 明天 23:59 |
| 12 | 00:00 的闹钟，now = 00:00:00.000 | 下一个周期 |

### 4.3 时区与夏令时

实现统一用 `ZonedDateTime`，时区取自调用方传入的 `now`。中国大陆无夏令时，但用 `ZonedDateTime` 而非 `LocalDateTime` 可以保证在用户出国旅行、设备切换时区时行为正确（跨 DST 边界时"明天 07:00"仍然是本地时间 07:00，而不是固定 24 小时之后）。

---

## 5. 响铃链路

### 5.1 完整流程

```
系统时间到
   ↓
AlarmManager.setAlarmClock() 唤醒进程
   ↓
AlarmReceiver.onReceive()
   ├─ 先启动 RingActivity（全屏 / 锁屏之上）        ← 先做不会失败的那件事
   ├─ 尝试 startForegroundService(RingService)      ← 包在 try/catch 里
   │     失败则记录降级标志，由 RingActivity 接管播放（见 5.6）
   ├─ 若为一次性闹钟 → 置 enabled = false
   └─ 调用 AlarmScheduler.scheduleAll()              ← 给重复闹钟排下一次
   ↓
RingAudioController（铃声播放的单一持有者，进程内单例）
   ├─ MediaPlayer 播放 RingtoneManager.getDefaultUri(TYPE_ALARM)
   │    AudioAttributes: USAGE_ALARM, CONTENT_TYPE_SONIFICATION
   ├─ Vibrator 震动（VibrationEffect 波形）
   └─ 无论由 RingService 还是 RingActivity 触发，都走这一个入口，杜绝双重播放
   ↓
RingActivity（setShowWhenLocked(true) + setTurnScreenOn(true)）
   ├─ onStart 时若发现播放尚未开始 → 自己调用 RingAudioController.start()
   ├─「关闭」→ RingAudioController.stop() + 停 Service + finish()
   └─「贪睡」→ SnoozeManager 注册 now + 10 分钟 + 停 Service + finish()
   ↓
RingService（前台服务，带常驻通知，含「关闭」「贪睡」两个 action）
   └─ 只负责：让铃声在 Activity 被系统抑制时仍能持续 + 提供通知入口
```

### 5.2 编排入口：AlarmScheduler.scheduleAll()

**这是唯一的重算入口，在 6 个时机被调用：**

1. 开机完成（`ACTION_BOOT_COMPLETED`）
2. 应用被更新后（`ACTION_MY_PACKAGE_REPLACED`）——**这条不能省**，见 5.6 的说明
3. 系统时间被修改（`ACTION_TIME_CHANGED`）
4. 系统时区被修改（`ACTION_TIMEZONE_CHANGED`）
5. 闹钟被新增 / 修改 / 删除 / 开关切换（由 Repository 调用）
6. 闹钟响铃后（由 `AlarmReceiver` 调用，为重复闹钟排下一次）

第 1~4 项由同一个 `SystemEventReceiver` 处理——它们的行为完全一致（都是调 `scheduleAll()`），拆成多个类没有收益。该 Receiver 在 manifest 中声明 `android:exported="false"`：按 `<receiver>` 的官方文档，`exported="false"` 的接收器**仍能收到系统广播**，只是收不到其他普通应用发来的广播。这四个广播全都是系统发出的受保护广播，因此 `exported="false"` 既够用又更安全。

它做的永远是同一件事：**读数据库所有 `enabled = true` 的闹钟 → 逐个算 `NextTrigger.next()` → 用 `setAlarmClock()` 注册**。

同时它会先取消所有已注册的闹钟再重新注册，保证不会有残留的陈旧注册。

### 5.3 PendingIntent 约定

- 统一使用 `PendingIntent.getBroadcast()`
- `requestCode = alarm.id.toInt()`，保证每个闹钟的 PendingIntent 唯一
- flags 固定为 `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`（Android 12+ 强制要求 `FLAG_IMMUTABLE`，否则崩溃）
- 取消时用完全相同的 `Intent` + `requestCode` + flags 构造，否则 `cancel()` 无效

### 5.4 贪睡的实现

贪睡**不写数据库、不修改闹钟记录**。`SnoozeManager` 直接向 `AlarmManager` 注册一个 `now + 10 分钟` 的一次性 `setAlarmClock()`，指向同一个 `AlarmReceiver`（通过一个独立的 `requestCode` 区间区分，例如 `alarm.id + 100_000`）。响铃 Activity 结束后这个注册自然失效。这样贪睡状态不需要持久化，也不会污染用户设置的闹钟。

`RingService` 需要能处理"上一次铃声还在响时新的触发到来"的情况：启动时先停止并释放已有的 `MediaPlayer` 实例。

### 5.5 AndroidManifest 权限清单

```xml
<!-- 精确闹钟：33+ 用 USE_EXACT_ALARM（闹钟类应用自动授予，无需引导用户去设置页）
     32 及以下用 SCHEDULE_EXACT_ALARM（Android 12/13 上同样是自动授予） -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**需要在代码里做运行时请求的只有 `POST_NOTIFICATIONS`**（Android 13 引入）。在 `MainActivity` 启动时检查并请求一次；若用户拒绝，闹钟仍会通过全屏 Activity 响铃，但前台服务的常驻通知不可见——需要在设置页或首次拒绝后给出提示。这是目标设备（Android 12/13）上唯一的运行时权限，精确闹钟权限在这一区间是自动授予的。

**Receiver 与 Service 的 manifest 声明要点：**

- `AlarmReceiver` / `SystemEventReceiver`：`android:exported="false"`。两者都只由系统或本应用自己的 PendingIntent 触发，不需要对外暴露
- `RingService`：`android:foregroundServiceType="mediaPlayback"`，`android:exported="false"`
- `RingActivity`：`android:showWhenLocked="true"`、`android:turnScreenOn="true"`、`android:excludeFromRecents="true"`、`android:launchMode="singleTask"`

### 5.6 后台启动前台服务的风险与降级路径

**这是本设计里唯一一处"已知会小概率失效"的地方，必须显式处理。**

Android 12（API 31）起，后台应用默认不能启动前台服务，违反会抛 `ForegroundServiceStartNotAllowedException`。官方豁免清单中确实包含「应用因精确闹钟被唤醒」这一条，`setAlarmClock()` 正好命中。

**但这条豁免在实践中并不可靠。** Google Issue Tracker 的 #238340795 记录了如下问题：即使命中豁免，`startForegroundService()` 仍会偶发失败，异常信息为 `mAllowStartForeground false`。该问题主要出现在应用从 `targetSdk 30` 升级到 31 的场景，Google 发布了修复，但**开发者实测在 Android 12 / 12L / 13 上仍有约 1/2500 的精确闹钟触发失败**。

对绝大多数应用，1/2500 是可以忽略的噪声。对闹钟应用，它的含义是"某天早上没响"。

**因此本设计不把响铃能力押在前台服务上：**

1. `AlarmReceiver` 收到触发后，**先启动 `RingActivity`**（启动 Activity 不受此限制），再尝试启动 `RingService`。
2. `startForegroundService()` 的调用**包在 `try/catch (ForegroundServiceStartNotAllowedException)` 中**，失败时仅记录日志并设置降级标志，不让异常中断 `onReceive()` 的后续步骤。
3. 铃声播放收敛到 `RingAudioController` 这一进程内单例，它有 `start()` / `stop()` 两个幂等方法。无论由 Service 还是 Activity 调用，效果一致，因此**不存在双重播放的可能**。
4. `RingActivity.onStart()` 时检查 `RingAudioController` 是否已在播放；若否（说明前台服务启动失败），由 Activity 自己启动播放。这就是降级路径。
5. 若连全屏 Activity 也被系统抑制（用户关闭了 `USE_FULL_SCREEN_INTENT`），`RingService` 的常驻通知是最后一道防线。两条路径互为备份，任一存活即可响铃。

**关于 `ACTION_MY_PACKAGE_REPLACED`（5.2 第 2 项）**：上述 issue 中另一个现象是——应用更新后，**更新前已注册的闹钟会失效**，且无法被取消或修改。Google 给出的规避办法就是"应用更新后重置所有闹钟"。这正是 `scheduleAll()` 必须在 `MY_PACKAGE_REPLACED` 时被调用的原因。虽然自用场景下更新频率不高，但这条只花一行代码，值得保留。

**降级路径无法通过自动化测试覆盖**（需要构造系统拒绝启动前台服务的场景）。它只能靠代码审查确认 `try/catch` 与 `RingAudioController` 的幂等性正确，并在真机验证清单第 7 条中确认 Activity 单独存在时铃声正常。

---

## 6. 界面

Compose + Material 3。主题使用动态取色（`dynamicColorScheme`，Android 12+ 支持，匹配目标设备）+ 深色模式跟随系统。

### 6.1 AlarmListScreen —— 主页

- 顶部一行摘要：「下一个闹钟：周三 07:00（还有 7 小时）」。计算方式：对所有启用闹钟取 `NextTrigger.next()` 的最小值；无启用闹钟时显示「没有启用的闹钟」
- `LazyColumn` 展示闹钟卡片：
  - 左侧超大号时间（Material 3 `displayLarge`）
  - 时间下方一行：标签 + 重复摘要（`RepeatDays.summary()`）
  - 右侧 `Switch` 控制启用状态
  - 已关闭的闹钟整张卡片降低透明度
- 卡片点击 → 编辑页；左滑 → 删除（带撤销提示）
- `FloatingActionButton` → 新建
- 空状态：「还没有闹钟，点右下角添加」

数据来自 `AlarmListViewModel`，订阅 `AlarmDao.observeAll()` 返回的 `Flow`，配合 `collectAsStateWithLifecycle()`。

### 6.2 AlarmEditScreen —— 新建 / 编辑

同一个 Composable，靠 `alarmId` 参数区分模式（`null` = 新建）。

- 顶部 `TimePicker`（Material 3），支持表盘与数字输入两种模式切换
- 重复：7 个圆形 `FilterChip`（一~日），外加三个预设快捷按钮「工作日」「每天」「仅一次」
- 标签：单行 `OutlinedTextField`，可留空
- 震动：`Switch`
- 右上角保存按钮；编辑模式下额外提供一个删除按钮（带二次确认）

状态由 `AlarmEditViewModel` 持有草稿（`Alarm` 的副本），保存时才写库并触发 `AlarmScheduler.scheduleAll()`。

### 6.3 RingActivity —— 全屏响铃

- 独立 Activity，不进入 Navigation Compose 的导航图
- 深色全屏，居中展示超大时间 + 标签 + 星期
- 底部两个大按钮：「贪睡 10 分钟」（次要样式） / 「关闭」（主要样式）
- 通过 `setShowWhenLocked(true)` + `setTurnScreenOn(true)` 在锁屏之上直接显示，无需解锁
- 返回键不关闭响铃（`onBackPressed` 拦截为无操作），只有点「关闭」或「贪睡」才结束

### 6.4 导航

Navigation Compose，两个目的地：`list`、`edit/{id}`。`RingActivity` 是独立 Activity，通过 `Intent` 启动，不参与导航图。

---

## 7. 测试策略

### 7.1 本地单元测试（`./gradlew test`，无需设备，秒级反馈）

TDD 主要在这层。

- **`NextTriggerTest`** —— 第 4.2 节表格中的全部 12 个场景。这是重头戏，边界条件在这里穷尽。
- **`RepeatDaysTest`** —— 掩码位序断言（bit0 = 周一）、各预设常量、`summary()` 的全部输出分支。
- **`AlarmRepositoryTest`**（可选）—— 用 fake DAO 验证 `AlarmEntity ↔ Alarm` 映射的往返一致性。

`NextTrigger` 和 `RepeatDays` 因为是纯 Kotlin、无 Android 依赖，可以直接在 `src/test/` 下用 JUnit 4 运行，不需要 Robolectric。

### 7.2 手动真机验证清单

实现完成后逐条验证。这份清单需要写进项目 README，作为每次改动的回归检查表。

1. 建一个 2 分钟后的闹钟 → 熄屏 → 到点全屏响铃 + 震动
2. 点「贪睡」→ 10 分钟后再次响铃
3. 点「关闭」→ 铃声停止；若是一次性闹钟，列表中该闹钟的开关自动变为关闭
4. 建一个「工作日」重复闹钟 → 修改系统日期逐天验证，只在周一~周五响
5. 重启手机 → 闹钟仍然有效（验证 `SystemEventReceiver`）
6. 手动修改系统时间（前后各调一次）→ 闹钟重算正确，不静默失效
7. 在最近任务列表里划掉 app → 闹钟仍然响（验证前台服务 + AlarmManager）
8. **熄屏静置 30 分钟 → Doze 下仍精确响**（最关键的一条）
9. 响铃过程中从通知栏点「关闭」→ 与在 Activity 里点关闭行为一致
10. 设备切换时区 → 重复闹钟仍按本地时间正确触发
11. 响铃时**不要出现两路铃声重叠**（验证 `RingAudioController` 的幂等性）
12. 连续触发同一个闹钟多次（如贪睡数次）→ 每次铃声都正常开始与停止，无残留

**第 7、8 条在模拟器上不可靠**（模拟器的 Doze 与后台限制行为与真机不同），必须真机验证。第 11、12 条是 5.6 节降级设计的主要风险点，需要重点确认。

---

## 8. 环境准备

开发机当前状态：有 Node 24 / Python 3.12，**没有 JDK、Android SDK、Android Studio**。以下步骤需要先完成。

### 8.1 安装路径（二选一）

**路径 A：安装 Android Studio（推荐用于练手）**
- 约 3 GB 下载
- 自带 JDK 17、Android SDK、模拟器、Logcat 调试器、Compose 预览
- 一站式图形界面，适合不熟悉 Android 工具链的情况

**路径 B：JDK 17 + Android command-line tools**
- 约 500 MB
- 用 `gradlew` 命令行构建，真机 USB 调试（需要 `adb`）
- 轻量，但没有模拟器和调试器 GUI

两条路径都需要在安装后接受 Android SDK licenses（`sdkmanager --licenses`）。

### 8.2 网络依赖（国内环境的坑）

首次构建需要从 Google Maven 仓库和 Maven Central 下载 Gradle 发行包与全部依赖，约 500 MB ~ 1 GB。**在国内网络环境下大概率需要配置阿里云镜像**，否则会长时间卡住甚至超时失败。

需要配置两处：
- `settings.gradle.kts` 中的 `pluginManagement` 和 `dependencyResolutionManagement` 仓库地址
- `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`（改用腾讯云或阿里云的 Gradle 发行包镜像）

这一步是实现的第 0 步，必须先跑通一个空的 "Hello World" 构建，确认工具链和网络都正常，再写业务代码。

### 8.3 真机调试准备

- 手机开启「开发者选项」→「USB 调试」
- USB 连接后 `adb devices` 应能看到设备
- **小米/华为/OPPO/vivo 等厂商 ROM 额外需要**：在「电池」或「应用管理」中把本应用加入「不受限制」/「允许后台运行」白名单，否则厂商的省电策略可能杀掉前台服务。这是国内 Android 闹钟类应用的通用坑，需要在 README 中写明。

---

## 9. 实施顺序概览

详细的任务拆解由后续的实现计划（writing-plans）产出。总体顺序：

1. **环境与骨架**：安装工具链、配置国内镜像、空项目构建通过
2. **domain 层（TDD）**：`RepeatDays` → `NextTrigger`，先写测试再写实现
3. **data 层**：Room 实体 / DAO / Repository，附映射测试
4. **alarm 层**：`AlarmScheduler` → `RingAudioController` → `AlarmReceiver` → `RingService` → `RingActivity` → `SystemEventReceiver`
5. **ui 层**：列表页 → 编辑页 → 响铃页 → 导航串联
6. **真机回归**：按第 7.2 节清单逐条验证

阶段 2 和 3 可以完全不碰设备完成；阶段 4 开始需要真机。

---

## 10. 参考资料

5.6 节的降级设计基于以下来源：

- [Restrictions on starting a foreground service from the background — Android Developers](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)（后台启动前台服务的豁免清单）
- [Issue 238340795: Exact Alarms causing ForegroundServiceStartNotAllowedException when update to target API 31 — Google Issue Tracker](https://issuetracker.google.com/issues/238340795)（豁免失效的已知问题与残留故障率）
- [Behavior changes: Apps targeting Android 12 — Android Developers](https://developer.android.com/about/versions/12/behavior-changes-12)（`SCHEDULE_EXACT_ALARM` 与 PendingIntent mutability 变更）
- [Exact alarm permissions and Google Play policy](https://support.google.com/googleplay/android-developer/answer/13161072)（`USE_EXACT_ALARM` 的适用范围——仅限闹钟/日历类核心功能）

`<receiver>` 的 `android:exported` 语义（5.2 节）依据 Android Developers 的 [`<receiver>` 清单元素文档](https://developer.android.com/guide/topics/manifest/receiver-element#exported)。

