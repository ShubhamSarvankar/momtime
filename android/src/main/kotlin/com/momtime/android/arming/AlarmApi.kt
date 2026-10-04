package com.momtime.android.arming

import android.app.AlarmManager
import android.app.PendingIntent

/**
 * The calls this app makes on `AlarmManager`, as a seam. Robolectric's `ShadowAlarmManager` never throws
 * `SecurityException` for an exact alarm without the capability (it only reports `canScheduleExactAlarms`),
 * so the one test that needs the platform to refuse an exact alarm replaces this to do so. Every other test
 * uses [PlatformAlarmApi] over the shadow, which is the oracle for what was armed.
 */
internal interface AlarmApi {
    /** Throws `SecurityException` on API 31 and above when exact alarms are not allowed. */
    fun setAlarmClock(
        triggerAtMillis: Long,
        show: PendingIntent?,
        operation: PendingIntent,
    )

    fun setAndAllowWhileIdle(
        triggerAtMillis: Long,
        operation: PendingIntent,
    )

    fun cancel(operation: PendingIntent)
}

internal class PlatformAlarmApi(
    private val alarmManager: AlarmManager,
) : AlarmApi {
    override fun setAlarmClock(
        triggerAtMillis: Long,
        show: PendingIntent?,
        operation: PendingIntent,
    ) = alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)

    override fun setAndAllowWhileIdle(
        triggerAtMillis: Long,
        operation: PendingIntent,
    ) = alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)

    override fun cancel(operation: PendingIntent) = alarmManager.cancel(operation)
}
