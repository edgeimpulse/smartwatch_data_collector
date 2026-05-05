package com.example.eidatacollector.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey

class PpgDataCollector(context: Context) {

    val ppgGreen = mutableListOf<Int>()
    val ppgIr    = mutableListOf<Int>()
    val ppgRed   = mutableListOf<Int>()

    private var onConnectResult: ((Boolean, String?) -> Unit)? = null
    private var tracker: HealthTracker? = null
    private var isTracking = false
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartTracker() }

    private val healthTrackingService = HealthTrackingService(
        object : ConnectionListener {
            override fun onConnectionSuccess() {
                onConnectResult?.invoke(true, null)
                onConnectResult = null
            }
            override fun onConnectionEnded() {}
            override fun onConnectionFailed(e: HealthTrackerException) {
                onConnectResult?.invoke(false, e.message)
                onConnectResult = null
            }
        },
        context
    )

    fun connect(onResult: (connected: Boolean, error: String?) -> Unit) {
        onConnectResult = onResult
        healthTrackingService.connectService()
    }

    var onTrackerError: ((HealthTracker.TrackerError) -> Unit)? = null

    fun start() {
        ppgGreen.clear()
        ppgIr.clear()
        ppgRed.clear()
        isTracking = true
        startTracker()
    }

    private fun startTracker() {
        tracker = healthTrackingService.getHealthTracker(
            HealthTrackerType.PPG_ON_DEMAND,
            setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)
        )
        tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val green = dp.getValue(ValueKey.PpgSet.PPG_GREEN) ?: continue
                    val ir    = dp.getValue(ValueKey.PpgSet.PPG_IR)    ?: continue
                    val red   = dp.getValue(ValueKey.PpgSet.PPG_RED)   ?: continue
                    ppgGreen.add(green)
                    ppgIr.add(ir)
                    ppgRed.add(red)
                }
            }
            override fun onFlushCompleted() {}
            override fun onError(error: HealthTracker.TrackerError) {
                onTrackerError?.invoke(error)
            }
        })
        // PPG_ON_DEMAND is capped at 30 s by the SDK — restart at 28 s to stay within limit
        if (isTracking) handler.postDelayed(restartRunnable, 28_000)
    }

    private fun restartTracker() {
        if (!isTracking) return
        tracker?.unsetEventListener()
        tracker = null
        startTracker()
    }

    fun stop() {
        isTracking = false
        handler.removeCallbacks(restartRunnable)
        tracker?.unsetEventListener()
        tracker = null
    }

    fun disconnect() {
        onConnectResult = null
        stop()
        runCatching { healthTrackingService.disconnectService() }
    }
}
