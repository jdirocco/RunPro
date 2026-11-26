package com.runpro.core.recording

/**
 * Deterministic traces generated in code, so the expected totals can be derived by hand.
 *
 * They cover what the committed `testdata/traces/` files do not (a climb, a traffic light, a pause with the
 * runner standing still); `TraceGoldenTotalsTest` in `:tools:replay` replays those files with golden totals.
 */
internal object SyntheticTraces {
    const val PAUSE_START_S = 900
    const val PAUSE_LENGTH_S = 60
    const val FIRST_LAP_S = 600
    const val SECOND_LAP_S = 1200
    const val DURATION_S = 1800

    /**
     * A 30 min easy run sampled at 1 Hz on the watch, timeline in timer seconds `τ` (pauses excluded):
     *
     * - 0–300 s warm-up at 2.5 m/s, HR 130, cadence 160; then 3.0 m/s, HR 155, cadence 170.
     * - 600–660 s climb of 30 m at 0.5 m/s, 1500–1560 s descent of 30 m.
     * - 900 s: 60 s manual pause; the runner stands still, samples keep coming (HR 110).
     * - 1200–1230 s: stands at a traffic light without pausing (no distance, cadence 0).
     * - 1500–1510 s: HR spike to 175.
     * - Manual laps at τ 600 and 1200, stop at τ 1800 (elapsed 1860 s).
     *
     * The watch is prepared 30 s before the start and sends samples meanwhile; they must not count.
     */
    fun easyThirtyMinutes(): List<EngineInput> = buildList {
        add(prepare(-30))
        for (s in -29..-1) {
            add(hr(s, 90))
            add(dist(s, 0.5 * (s + 30)))
        }
        add(start(0))
        for (tau in 0..DURATION_S) {
            if (tau == PAUSE_START_S) {
                addPause(PAUSE_START_S)
            }
            val at = elapsedAt(tau)
            addSamples(at, tau)
            when (tau) {
                FIRST_LAP_S, SECOND_LAP_S -> add(lap(at))
                DURATION_S -> add(stop(at))
                else -> add(tick(at))
            }
        }
    }

    private fun MutableList<EngineInput>.addPause(pausedAt: Int) {
        addSamples(pausedAt, PAUSE_START_S)
        add(pause(pausedAt))
        for (s in pausedAt + 1 until pausedAt + PAUSE_LENGTH_S) {
            add(hr(s, 110))
            add(dist(s, distanceAt(PAUSE_START_S)))
            add(cad(s, 0.0))
            add(elev(s, elevationAt(PAUSE_START_S)))
            add(tick(s))
        }
        add(resume(pausedAt + PAUSE_LENGTH_S))
    }

    private fun MutableList<EngineInput>.addSamples(at: Int, tau: Int) {
        add(hr(at, heartRateAt(tau)))
        add(dist(at, distanceAt(tau)))
        add(cad(at, cadenceAt(tau)))
        add(elev(at, elevationAt(tau)))
        add(speed(at, if (tau in 1200 until 1230) 0.0 else 3.0))
    }

    /**
     * Elapsed seconds for timer second [tau]: the pause shifts everything from its end by its length.
     * The samples of τ 900 are sent twice, before the pause and at the resume.
     */
    private fun elapsedAt(tau: Int) = if (tau < PAUSE_START_S) tau else tau + PAUSE_LENGTH_S

    private fun distanceAt(tau: Int): Double = when {
        tau <= 300 -> 2.5 * tau
        tau <= 1200 -> 750.0 + 3.0 * (tau - 300)
        tau <= 1230 -> 3450.0
        else -> 3450.0 + 3.0 * (tau - 1230)
    }

    private fun heartRateAt(tau: Int): Int = when {
        tau < 300 -> 130
        tau in 1500 until 1510 -> 175
        else -> 155
    }

    private fun cadenceAt(tau: Int): Double = when {
        tau < 300 -> 160.0
        tau in 1200 until 1230 -> 0.0
        else -> 170.0
    }

    private fun elevationAt(tau: Int): Double = when {
        tau < 600 -> 100.0
        tau < 660 -> 100.0 + 0.5 * (tau - 600)
        tau < 1500 -> 130.0
        tau < 1560 -> 130.0 - 0.5 * (tau - 1500)
        else -> 100.0
    }
}
