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
