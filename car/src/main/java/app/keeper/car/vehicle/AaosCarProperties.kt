package app.keeper.car.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import app.keeper.core.Gear

/**
 * Android Automotive 표준 차량 속성 (Pleos Connect는 AAOS 기반).
 * - GEAR_SELECTION : 기어 (Pleos Vehicle SDK의 Gear API가 이 속성으로 대체 권고됨)
 * - PERF_VEHICLE_SPEED : 차속 m/s → km/h  ("주행 중에는 잠그지 않는다" 판단에 사용)
 * 일반 스마트폰에는 android.car 라이브러리가 없으므로 모든 호출을 보호한다.
 */
class AaosCarProperties(private val context: Context) {
    private var car: Any? = null
    private var callback: Any? = null

    fun start(onGear: (Gear) -> Unit, onSpeed: (Float) -> Unit): Boolean = try {
        val c = Car.createCar(context)
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                when (value.propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> onGear(mapGear(value.value as Int))
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> onSpeed(((value.value as Float) * 3.6f).coerceAtLeast(0f))
                }
            }
            override fun onErrorEvent(propId: Int, zone: Int) {}
        }
        callback = cb
        val okGear = pm.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        pm.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        okGear
    } catch (t: Throwable) {
        false
    }

    fun stop() {
        runCatching {
            val c = car as? Car ?: return
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            (callback as? CarPropertyManager.CarPropertyEventCallback)?.let { pm.unregisterCallback(it) }
            c.disconnect()
        }
    }

    // android.car.VehicleGear 상수: NEUTRAL=0x1, REVERSE=0x2, PARK=0x4, DRIVE=0x8, 1~9단=0x10~
    private fun mapGear(v: Int): Gear = when (v) {
        0x4 -> Gear.PARK
        0x2 -> Gear.REVERSE
        0x1 -> Gear.NEUTRAL
        0 -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }
}
