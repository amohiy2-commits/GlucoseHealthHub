package com.example.glucosehealthhub

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Session span is NOT the same as time asleep. Actual sleep time is calculated
 * only from explicit sleep-stage intervals (LIGHT, DEEP, REM, SLEEPING).
 * Uncovered/unknown intervals are never guessed to be sleep.
 */
internal data class SleepOverview(
    val start: Instant,
    val end: Instant,
    val provider: String,
    val sessionMinutes: Long,
    val asleepMinutes: Long?,
    val awakeMinutes: Long?,
    val lightMinutes: Long?,
    val deepMinutes: Long?,
    val remMinutes: Long?,
    val stageCount: Int,
) {
    val wakeDay: LocalDate get() = end.atZone(ZoneId.systemDefault()).toLocalDate()
}

internal data class SleepReadResult(
    val sessions: List<SleepOverview>,
    val fetchedCount: Int,
    val pageLimitReached: Boolean,
)

private fun stageMinutes(record: SleepSessionRecord, vararg codes: Int): Long {
    return record.stages
        .filter { it.stage in codes }
        .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0L) }
}

internal fun toSleepOverview(record: SleepSessionRecord): SleepOverview {
    val hasStages = record.stages.isNotEmpty()
    val asleep = if (hasStages) stageMinutes(
        record,
        SleepSessionRecord.STAGE_TYPE_SLEEPING,
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP,
        SleepSessionRecord.STAGE_TYPE_REM
    ) else null
    return SleepOverview(
        start = record.startTime,
        end = record.endTime,
        provider = record.metadata.dataOrigin.packageName,
        sessionMinutes = Duration.between(record.startTime, record.endTime).toMinutes().coerceAtLeast(0L),
        asleepMinutes = asleep,
        awakeMinutes = if (hasStages) stageMinutes(
            record,
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        ) else null,
        lightMinutes = if (hasStages) stageMinutes(record, SleepSessionRecord.STAGE_TYPE_LIGHT) else null,
        deepMinutes = if (hasStages) stageMinutes(record, SleepSessionRecord.STAGE_TYPE_DEEP) else null,
        remMinutes = if (hasStages) stageMinutes(record, SleepSessionRecord.STAGE_TYPE_REM) else null,
        stageCount = record.stages.size,
    )
}

/**
 * Search the last 29 days (within Health Connect's usual 30-day history access).
 * Only read data. Do not write, synthesize or silently import fake sleep entries.
 */
internal suspend fun readSleepHistory(
    client: HealthConnectClient,
    begin: Instant = Instant.now().minus(Duration.ofDays(29)),
    end: Instant = Instant.now(),
): SleepReadResult {
    val collected = mutableListOf<SleepSessionRecord>()
    var token: String? = null
    var pageCount = 0
    var limitReached = false
    do {
        val result = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(begin, end),
                ascendingOrder = false,
                pageSize = 100,
                pageToken = token,
            )
        )
        collected += result.records
        token = result.pageToken?.takeIf { it.isNotBlank() }
        pageCount++
        if (pageCount >= 10 && token != null) {
            limitReached = true
            break
        }
    } while (token != null)

    // Different providers can have overlapping entries. Keep source identity,
    // do not sum their sleep stages or mistake duplicates for independent nights.
    val sessions = collected
        .distinctBy { Triple(it.startTime, it.endTime, it.metadata.dataOrigin.packageName) }
        .map(::toSleepOverview)
        .sortedByDescending { it.end }
    return SleepReadResult(
        sessions = sessions,
        fetchedCount = collected.size,
        pageLimitReached = limitReached,
    )
}
