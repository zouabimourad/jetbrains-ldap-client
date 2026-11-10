package com.mouradzouabi.ldapclient.ui

import com.intellij.platform.util.progress.SequentialProgressReporter
import com.intellij.platform.util.progress.reportSequentialProgress

/**
 * Text and fraction updates on top of the stable step-based progress API.
 * Not thread-safe: call [update] from one thread at a time. Updates are throttled.
 */
class ProgressUpdater(private val reporter: SequentialProgressReporter) {
    private var done = 0
    private var lastUpdate = 0L

    /** Shows [text]; with [fraction] (0..1, never decreasing) also advances the progress bar. */
    fun update(text: String, fraction: Double? = null) {
        val now = System.nanoTime()
        if (now - lastUpdate < THROTTLE_NANOS) return
        lastUpdate = now
        val target = fraction?.let { (it.coerceIn(0.0, 1.0) * TOTAL).toInt().coerceAtLeast(done) } ?: done
        reporter.sizedStep(target - done, text)
        done = target
    }

    companion object {
        const val TOTAL = 1000
        private const val THROTTLE_NANOS = 100_000_000L
    }
}

suspend inline fun <T> reportProgressUpdates(action: (ProgressUpdater) -> T): T =
    reportSequentialProgress(ProgressUpdater.TOTAL) { action(ProgressUpdater(it)) }
