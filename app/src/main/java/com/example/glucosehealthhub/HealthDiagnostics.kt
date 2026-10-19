package com.example.glucosehealthhub

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Health Connect is a data store, not a live connection to Samsung Health.
// Permissions for the reading app and data-provider writing app are distinct.
internal data class HealthMetric(
    val permissionGranted: Boolean = false,
    val summary: String = "Not checked",
    val details: String = "",
    val source: String? = null,
    val lastRecordedAt: Instant? = null,
)

internal data class HealthSnapshot(
    val stepsToday: Long? = null,
    val stepsSevenDays: Long? = null,
    val latestHeartBpm: Long? = null,
    val averageHeartSevenDays: Long? = null,
    val latestSleepSessionHours: Double? = null,
    val steps: HealthMetric = HealthMetric(),
    val heart: HealthMetric = HealthMetric(),
    val sleep: HealthMetric = HealthMetric(),
) {
    val permittedCount: Int get() = listOf(steps, heart, sleep).count { it.permissionGranted }
    val missingData: Boolean get() =
        (stepsToday == null && stepsSevenDays == null &&
         latestHeartBpm == null && latestSleepSessionHours == null)
}

internal suspend fun checkHealthConnect(client: HealthConnectClient): HealthSnapshot {
    val granted = client.permissionController.getGrantedPermissions()
    val now = Instant.now()
    val historyStart = now.minus(Duration.ofDays(29))
    val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
    val weekStart = now.minus(Duration.ofDays(7))
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
    val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)

    var todaySteps: Long? = null
    var weekSteps: Long? = null
    var stepsMetric = HealthMetric(permissionGranted = stepsPermission in granted)
    if (stepsPermission in granted) {
        stepsMetric = try {
            todaySteps = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(todayStart, now)
                )
            )[StepsRecord.COUNT_TOTAL]
            weekSteps = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(weekStart, now)
                )
            )[StepsRecord.COUNT_TOTAL]
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(historyStart, now),
                    pageSize = 100,
                    ascendingOrder = false,
                )
            ).records
            val latest = records.maxByOrNull { it.endTime }
            val available = todaySteps != null || weekSteps != null || records.isNotEmpty()
            HealthMetric(
                permissionGranted = true,
                summary = if (available) "Data found" else "No steps in Health Connect (29 days)",
                details = "Read access granted. Recent raw records sampled: ${records.size}. " +
                    if (available) "Samsung/provider data available." else "Check Samsung Health WRITE access.",
                source = latest?.metadata?.dataOrigin?.packageName,
                lastRecordedAt = latest?.endTime,
            )
        } catch (e: Exception) {
            HealthMetric(true, "Steps query error", "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
        }
    } else stepsMetric = HealthMetric(false, "Steps permission missing", "Allow Glucose Health Hub to READ steps.")

    var latestHeart: Long? = null
    var heartAverage: Long? = null
    var heartMetric = HealthMetric(permissionGranted = heartPermission in granted)
    if (heartPermission in granted) {
        heartMetric = try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(historyStart, now),
                    pageSize = 100,
                    ascendingOrder = false,
                )
            ).records
            val lastRecordAndSample = records.asSequence()
                .flatMap { record -> record.samples.asSequence().map { sample -> record to sample } }
                .maxByOrNull { it.second.time }
            latestHeart = lastRecordAndSample?.second?.beatsPerMinute
            heartAverage = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(weekStart, now)
                )
            )[HeartRateRecord.BPM_AVG]
            val available = latestHeart != null || heartAverage != null
            HealthMetric(
                permissionGranted = true,
                summary = if (available) "Data found" else "No heart rate in Health Connect (29 days)",
                details = "Recent records sampled: ${records.size}. " +
                    if (available) "Latest measurement shown below." else "Check Samsung Health WRITE access.",
                source = lastRecordAndSample?.first?.metadata?.dataOrigin?.packageName
                    ?: records.firstOrNull()?.metadata?.dataOrigin?.packageName,
                lastRecordedAt = lastRecordAndSample?.second?.time,
            )
        } catch (e: Exception) {
            HealthMetric(true, "Heart rate query error", "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
        }
    } else heartMetric = HealthMetric(false, "Heart rate permission missing", "Allow Glucose Health Hub to READ heart rate.")

    var latestSleepHours: Double? = null
    var sleepMetric = HealthMetric(permissionGranted = sleepPermission in granted)
    if (sleepPermission in granted) {
        sleepMetric = try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(historyStart, now),
                    pageSize = 100,
                    ascendingOrder = false,
                )
            ).records
            val latest = records.maxByOrNull { it.endTime }
            latestSleepHours = latest?.let {
                Duration.between(it.startTime, it.endTime).toMinutes().toDouble() / 60
            }
            HealthMetric(
                permissionGranted = true,
                summary = if (latest != null) "Data found" else "No sleep sessions in Health Connect (29 days)",
                details = "Recent sessions sampled: ${records.size}. " +
                    if (latest != null) "Showing last sleep session span (not sleep-stage-adjusted)." else "Check Samsung Health WRITE access.",
                source = latest?.metadata?.dataOrigin?.packageName,
                lastRecordedAt = latest?.endTime,
            )
        } catch (e: Exception) {
            HealthMetric(true, "Sleep query error", "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
        }
    } else sleepMetric = HealthMetric(false, "Sleep permission missing", "Allow Glucose Health Hub to READ sleep sessions.")

    return HealthSnapshot(
        stepsToday = todaySteps, stepsSevenDays = weekSteps,
        latestHeartBpm = latestHeart, averageHeartSevenDays = heartAverage,
        latestSleepSessionHours = latestSleepHours,
        steps = stepsMetric, heart = heartMetric, sleep = sleepMetric
    )
}
