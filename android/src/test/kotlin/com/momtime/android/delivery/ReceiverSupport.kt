package com.momtime.android.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives [receiver] the pending result the platform gives a receiver it dispatches, so `goAsync()` returns a real one
 * whose `finish()` can be observed. Called directly, a receiver has none.
 */
internal fun withPendingResult(receiver: BroadcastReceiver): BroadcastReceiver.PendingResult {
    val pending =
        ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java,
            "create",
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
    ReflectionHelpers.callInstanceMethod<Any>(
        receiver,
        "setPendingResult",
        ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending),
    )
    return pending
}

/** Delivers [intent] to a new [RingActionReceiver] and returns its pending result. */
internal fun deliverToActionReceiver(
    context: Context,
    intent: Intent,
): BroadcastReceiver.PendingResult {
    val receiver = RingActionReceiver()
    val pending = withPendingResult(receiver)
    shadowOf(receiver).onReceive(context, intent, AtomicBoolean())
    return pending
}

/** True once the broadcast the receiver took was finished, which is after the action ran. */
internal fun finished(pending: BroadcastReceiver.PendingResult): Boolean {
    val future = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
    return try {
        future.get(10, TimeUnit.SECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }
}
