package com.metaforge.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * Live progress reporting used by every long running operation in the app.
 *
 * Nothing in MetaForge shows a bare spinner. Each operation declares its stages
 * up front, then reports which one is running, how far along it is, and how long
 * each finished stage actually took, so the UI can render a real checklist.
 */
data class Stage(
    val id: String,
    val label: String,
)

enum class StageState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class StageProgress(
    val stage: Stage,
    val state: StageState,
    val fraction: Float? = null,
    val detail: String? = null,
    val elapsedMs: Long? = null,
)

/** A full snapshot of an operation, safe to render directly. */
data class OperationProgress(
    val title: String,
    val stages: List<StageProgress>,
    val overallFraction: Float,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val current: StageProgress? get() = stages.firstOrNull { it.state == StageState.RUNNING }
}

/**
 * Emits [OperationProgress] snapshots as the work advances.
 * Use inside a `flow { }` builder: the collector sees every transition.
 */
class ProgressReporter(
    private val title: String,
    stages: List<Stage>,
    private val collector: FlowCollector<OperationProgress>,
) {
    private val states = stages.map { StageProgress(it, StageState.PENDING) }.toMutableList()
    private var startedAt = 0L

    private fun indexOf(id: String) = states.indexOfFirst { it.stage.id == id }

    private suspend fun emit(finished: Boolean = false, error: String? = null) {
        val done = states.count { it.state == StageState.DONE || it.state == StageState.SKIPPED }
        val running = states.firstOrNull { it.state == StageState.RUNNING }
        val partial = running?.fraction ?: 0f
        val overall = ((done + partial) / states.size).coerceIn(0f, 1f)
        collector.emit(OperationProgress(title, states.toList(), overall, finished, error))
    }

    suspend fun start(id: String, detail: String? = null) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        startedAt = System.nanoTime()
        states[i] = states[i].copy(state = StageState.RUNNING, fraction = 0f, detail = detail)
        emit()
    }

    suspend fun update(id: String, fraction: Float, detail: String? = null) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        states[i] = states[i].copy(fraction = fraction.coerceIn(0f, 1f), detail = detail ?: states[i].detail)
        emit()
    }

    suspend fun done(id: String, detail: String? = null) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        val ms = if (startedAt == 0L) null else (System.nanoTime() - startedAt) / 1_000_000
        states[i] = states[i].copy(state = StageState.DONE, fraction = 1f, detail = detail, elapsedMs = ms)
        emit()
    }

    suspend fun skip(id: String, why: String) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        states[i] = states[i].copy(state = StageState.SKIPPED, detail = why)
        emit()
    }

    suspend fun fail(id: String, why: String) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        states[i] = states[i].copy(state = StageState.FAILED, detail = why)
        emit(finished = true, error = why)
    }

    suspend fun finish() = emit(finished = true)

    /** Runs [block] as a stage, marking it done or failed automatically. */
    suspend fun <T> stage(id: String, detail: String? = null, block: suspend () -> T): T {
        start(id, detail)
        return try {
            block().also { done(id) }
        } catch (t: Throwable) {
            fail(id, t.message ?: t::class.simpleName ?: "failed")
            throw t
        }
    }
}

/** Convenience: build a progress-reporting flow. */
inline fun progressFlow(
    title: String,
    stages: List<Stage>,
    crossinline body: suspend ProgressReporter.() -> Unit,
): Flow<OperationProgress> = kotlinx.coroutines.flow.flow {
    val reporter = ProgressReporter(title, stages, this)
    try {
        reporter.body()
        reporter.finish()
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
    }
}
