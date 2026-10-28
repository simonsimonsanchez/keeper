package app.keeper.car.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.os.Handler
import android.os.Looper

data class GeoPoint(val lat: Double, val lng: Double)

/** 위치: 무단 운행 시 위치 추적, 발레 모드 반경 이탈 감지. */
interface LocationGateway {
    val isSimulated: Boolean
    fun startTracking(onLocation: (GeoPoint) -> Unit)
    fun stopTracking()
    fun addGeofence(radiusMeters: Int, onExit: () -> Unit)
    fun removeGeofence()
    fun release() {}
    /** 시연용: 반경 이탈을 직접 발생시킨다. */
    fun simulateExit() {}

    companion object {
        fun create(context: Context, simulated: Boolean): LocationGateway {
            if (!simulated) {
                val fused = runCatching { FusedLocation(context.applicationContext).also { it.initialize() } }.getOrNull()
                if (fused != null && runCatching { fused.isSupported() }.getOrDefault(false)) return PleosLocationGateway(fused)
            }
            return SimulatedLocationGateway()
        }
    }
}

/** Pleos FusedLocation SDK: registerFusedLocationCallback + addProximityAlert(EXIT). */
class PleosLocationGateway(private val fused: FusedLocation) : LocationGateway {
    override val isSimulated = false
    private var trackingListener: FusedLocationListener? = null
    private var alertId: String? = null

    override fun startTracking(onLocation: (GeoPoint) -> Unit) {
        stopTracking()
        val l = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) { if (data != null) onLocation(GeoPoint(data.latitude, data.longitude)) }
            override fun onError(e: Exception) {}
        }
        trackingListener = l
        fused.registerFusedLocationCallback(l)
    }

    override fun stopTracking() {
        trackingListener?.let { fused.unregisterFusedLocationCallback(it) }
        trackingListener = null
    }

    override fun addGeofence(radiusMeters: Int, onExit: () -> Unit) {
        removeGeofence()
        // 발레를 맡긴 현재 위치를 중심으로 반경 설정
        fused.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data == null) return
                val req = ProximityAlertRequest(data.latitude, data.longitude, radiusMeters.toFloat(), setOf(ProximityTransition.EXIT))
                fused.addProximityAlert(req, object : ProximityAlertListener {
                    override fun onRegistered(id: String) { alertId = id }
                    override fun onTransition(event: ProximityAlertEvent) {
                        if (event.transition == ProximityTransition.EXIT) onExit()
                    }
                    override fun onError(e: Exception) {}
                })
            }
            override fun onError(e: Exception) {}
        })
    }

    override fun removeGeofence() {
        alertId?.let { runCatching { fused.removeProximityAlert(it) } }
        alertId = null
    }

    override fun release() { stopTracking(); removeGeofence(); runCatching { fused.release() } }
}

/** 시연 모드: 서울시청 부근에서 출발해 조금씩 움직이는 가짜 위치. */
class SimulatedLocationGateway : LocationGateway {
    override val isSimulated = true
    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private var ticker: Runnable? = null
    private var exitCb: (() -> Unit)? = null

    override fun startTracking(onLocation: (GeoPoint) -> Unit) {
        stopTracking()
        val r = object : Runnable {
            override fun run() {
                step++
                onLocation(GeoPoint(37.5665 + step * 0.0009, 126.9780 + step * 0.0011))
                handler.postDelayed(this, 3000)
            }
        }
        ticker = r
        handler.post(r)
    }

    override fun stopTracking() { ticker?.let(handler::removeCallbacks); ticker = null; step = 0 }
    override fun addGeofence(radiusMeters: Int, onExit: () -> Unit) { exitCb = onExit }
    override fun removeGeofence() { exitCb = null }
    override fun simulateExit() { exitCb?.invoke() }
}
