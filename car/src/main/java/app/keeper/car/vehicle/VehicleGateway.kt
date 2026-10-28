@file:Suppress("DEPRECATION")
package app.keeper.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.content.Intent
import android.util.Log
import app.keeper.core.Gear
import app.keeper.core.VehicleSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 차량 신호 읽기 + 최소한의 차량 제어. 실제 Pleos 차량과 시연(시뮬레이션) 구현을 같은 모양으로 쓴다. */
interface VehicleGateway {
    val signals: StateFlow<VehicleSignals>
    val isSimulated: Boolean
    val lastError: StateFlow<String?>
    fun start()
    fun stop()
    fun lockAllDoors()
    /** 주차 상태에서만 호출된다: 실내등 켜기 + 앰비언트 라이트 경고색. */
    fun setParkedWarningLights(on: Boolean)
    /** 시연 모드 전용: 신호를 손으로 바꾼다. */
    fun simulate(change: (VehicleSignals) -> VehicleSignals) {}

    companion object {
        /** Pleos Vehicle Service가 설치된 차량(또는 Pleos 에뮬레이터)이면 실제 SDK, 아니면 시연 모드. */
        fun create(context: Context): VehicleGateway {
            val hasService = context.packageManager
                .queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0)
                .isNotEmpty()
            return if (hasService) PleosVehicleGateway(context) else SimulatedVehicleGateway()
        }
    }
}

// =====================================================================================
// 실제 Pleos Connect 차량 — Vehicle SDK 2.3.2
// =====================================================================================
class PleosVehicleGateway(context: Context) : VehicleGateway {
    override val isSimulated = false
    private val _signals = MutableStateFlow(VehicleSignals())
    override val signals: StateFlow<VehicleSignals> = _signals
    private val _error = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _error

    private val vehicle = Vehicle(context.applicationContext)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)   // 좌핸들 기준 운전석
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val allDoors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

    private val fail: (Exception) -> Unit = { e -> _error.value = e.message; Log.w(TAG, "vehicle", e) }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = _signals.update { it.copy(gear = gear.toGear()) }
        override fun onFailed(e: Exception) = fail(e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = _signals.update { it.copy(brakePressed = pressed == true) }
        override fun onFailed(e: Exception) = fail(e)
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = _signals.update { it.copy(driverSeated = occupied == true) }
        override fun onFailed(e: Exception) = fail(e)
    }
    private val doorLockListener = object : DoorLockStateListener {
        override fun onDoorLockStateUpdated(locked: Boolean?) = _signals.update { it.copy(doorsLocked = locked == true) }
        override fun onFailed(e: Exception) = fail(e)
    }

    private val aaos = AaosCarProperties(context.applicationContext)

    override fun start() {
        // 기어·속도: AAOS 표준 CarPropertyManager 우선 (Pleos Gear API는 deprecated → 대체 경로)
        val aaosOk = aaos.start(
            onGear = { g -> _signals.update { it.copy(gear = g) } },
            onSpeed = { kmh -> _signals.update { it.copy(speedKmh = kmh) } },
        )
        runCatching {
            vehicle.initialize()
            // 1) 현재 값 읽기
            if (!aaosOk) vehicle.getGear().getCurrentGearSelectionState({ g: VehicleGear? -> _signals.update { it.copy(gear = g.toGear()) } }, fail)
            vehicle.getBrake().getCurrentBrakePedalStatus({ b -> _signals.update { it.copy(brakePressed = b == true) } }, fail)
            vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, { o -> _signals.update { it.copy(driverSeated = o == true) } }, fail)
            vehicle.getDoor().getDoorLockState(driverDoor, { l -> _signals.update { it.copy(doorsLocked = l == true) } }, fail)
            // 2) 변화 구독
            if (!aaosOk) vehicle.getGear().registerGearSelection(gearListener)
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
            vehicle.getSeat().registerSeatOccupancy(driverSeat, seatListener)
            vehicle.getDoor().registerDoorLockState(driverDoor, doorLockListener)
        }.onFailure { fail(it as? Exception ?: Exception(it)) }
    }

    override fun stop() {
        aaos.stop()
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getSeat().unregisterSeatOccupancy(driverSeat, seatListener)
            vehicle.getDoor().unregisterDoorLockState(driverDoor, doorLockListener)
            vehicle.release()
        }
    }

    override fun lockAllDoors() {
        allDoors.forEach { d ->
            runCatching { vehicle.getDoor().setDoorLock(DoorArea(d), true, { _, _ -> }, fail) }
        }
    }

    override fun setParkedWarningLights(on: Boolean) {
        // 안전: 주행 중에는 절대 조명을 바꾸지 않는다 (GuardEngine도 주차 중에만 요청)
        if (on && _signals.value.isMoving) return
        val sw = if (on) LightSwitch.ON else LightSwitch.OFF
        runCatching { vehicle.getLight().setCabinLightSwitch(sw, {}, fail) }
        runCatching {
            vehicle.getLight().setAmbientLightSwitch(driverSeat, sw, { _, _ -> }, fail)
            if (on) vehicle.getLight().setAmbientLightBrightnessColor(driverSeat, WARNING_RED, { _, _ -> }, fail)
        }
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        null -> Gear.UNKNOWN
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // D, 1~9단
    }

    companion object {
        private const val TAG = "KEEPER"
        private const val WARNING_RED = 0xFF2D2D
    }
}

// =====================================================================================
// 시연 모드 — 일반 안드로이드 폰/태블릿에서 화면의 시연 패널로 신호를 바꿔 본다
// =====================================================================================
class SimulatedVehicleGateway : VehicleGateway {
    override val isSimulated = true
    private val _signals = MutableStateFlow(VehicleSignals())
    override val signals: StateFlow<VehicleSignals> = _signals
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    val warningLights = MutableStateFlow(false)
    override fun start() {}
    override fun stop() {}
    override fun lockAllDoors() = _signals.update { it.copy(doorsLocked = true) }
    override fun setParkedWarningLights(on: Boolean) { if (!_signals.value.isMoving || !on) warningLights.value = on }
    override fun simulate(change: (VehicleSignals) -> VehicleSignals) = _signals.update(change)
}
