package app.keeper.car

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keeper.car.vehicle.LocationGateway
import app.keeper.car.vehicle.SimulatedVehicleGateway
import app.keeper.car.vehicle.VehicleGateway
import app.keeper.core.Alert
import app.keeper.core.CommandSource
import app.keeper.core.GuardConfig
import app.keeper.core.GuardEngine
import app.keeper.core.GuardEvent
import app.keeper.core.GuardMode
import app.keeper.core.GuardState
import app.keeper.core.Phase
import app.keeper.core.PinHasher
import app.keeper.core.VehicleSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogItem(val time: String, val text: String)
data class OwnerAlert(val time: String, val title: String, val message: String, val danger: Boolean)

enum class PadPurpose { UNLOCK, DISARM, SETUP_1, SETUP_2, CHANGE_VERIFY, CHANGE_NEW, FAMILY_NEW }

data class CarUi(
    val needsSetup: Boolean = false,
    val simulated: Boolean = true,
    val locationSimulated: Boolean = true,
    val signals: VehicleSignals = VehicleSignals(),
    val guard: GuardState = GuardState(),
    val lockVisible: Boolean = false,
    val lockValet: Boolean = false,
    val pad: PadPurpose? = null,
    val pinInput: String = "",
    val pinMessage: String? = null,
    val pinError: Boolean = false,
    val lockoutSec: Int = 0,
    val keypadOrder: List<Int> = (1..9).toList() + 0,
    val logs: List<LogItem> = emptyList(),
    val alerts: List<OwnerAlert> = emptyList(),
    val warningLights: Boolean = false,
    val tracking: Boolean = false,
    val lastLocation: String? = null,
    val issuedValetPin: String? = null,
    val valetReport: String? = null,
    val hasFamily: Boolean = false,
    val autoRelock: Boolean = true,
    val shuffleKeypad: Boolean = true,
    val useFingerprint: Boolean = true,
    val toast: String? = null,
)

class CarViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {
    private val prefs = PinPrefs(app)
    private val engine = GuardEngine(prefs, GuardConfig(autoRelock = prefs.autoRelock))
    val vehicle: VehicleGateway = VehicleGateway.create(app)
    private val location: LocationGateway = LocationGateway.create(app, vehicle.isSimulated)
    private val tts = TextToSpeech(app, this)
    private var ttsReady = false
    private var pendingNewPin: String? = null

    private val _ui = MutableStateFlow(
        CarUi(
            needsSetup = !prefs.isSetUp,
            simulated = vehicle.isSimulated,
            locationSimulated = location.isSimulated,
            pad = if (!prefs.isSetUp) PadPurpose.SETUP_1 else null,
            hasFamily = prefs.hasFamily,
            autoRelock = prefs.autoRelock,
            shuffleKeypad = prefs.shuffleKeypad,
            useFingerprint = prefs.useFingerprint,
        )
    )
    val ui: StateFlow<CarUi> = _ui

    init {
        vehicle.start()
        viewModelScope.launch {
            vehicle.signals.collect { s ->
                _ui.update { it.copy(signals = s) }
                handle(engine.onSignals(s, now()))
            }
        }
        (vehicle as? SimulatedVehicleGateway)?.let { sim ->
            viewModelScope.launch { sim.warningLights.collect { on -> _ui.update { it.copy(warningLights = on) } } }
        }
        viewModelScope.launch {
            while (true) {
                handle(engine.tick(now()))
                val st = engine.state
                val left = if (st.lockoutUntil > now()) ((st.lockoutUntil - now()) / 1000).toInt() + 1 else 0
                _ui.update { it.copy(lockoutSec = left, guard = st) }
                delay(1000)
            }
        }
        log(if (vehicle.isSimulated) "시연 모드로 시작 (Pleos Vehicle Service 없음)" else "Pleos Vehicle SDK 연결")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            ttsReady = true
        }
    }

    // ------------------------------------------------------------------ 사용자 동작

    fun armFromCar() = handle(engine.arm(now(), CommandSource.CAR_SCREEN))
    fun requestDisarm() = openPad(PadPurpose.DISARM)

    /** 스마트폰 원격 명령 (실서비스: 현대차 커넥티드 서버 경유 → 차량 앱 수신). 시연 패널에서 호출. */
    fun remoteArm() = handle(engine.arm(now(), CommandSource.PHONE))
    fun remoteDisarm() = handle(engine.disarm(CommandSource.PHONE))

    fun startValet(minutes: Int, radius: Int) {
        val pin = PinHasher.randomPin()
        handle(engine.startValet(now(), pin, minutes, radius, CommandSource.CAR_SCREEN))
        _ui.update { it.copy(issuedValetPin = pin, valetReport = null) }
    }
    fun endValet() = handle(engine.endValet(now(), CommandSource.CAR_SCREEN))
    fun dismissValetPin() = _ui.update { it.copy(issuedValetPin = null) }
    fun dismissReport() = _ui.update { it.copy(valetReport = null) }

    fun openPad(purpose: PadPurpose) = _ui.update {
        it.copy(pad = purpose, pinInput = "", pinMessage = null, pinError = false, keypadOrder = keypad())
    }
    fun closePad() = _ui.update { it.copy(pad = null, pinInput = "", pinMessage = null) }

    fun digit(d: Int) {
        val cur = _ui.value
        if (cur.pinInput.length >= 4) return
        val next = cur.pinInput + d
        _ui.update { it.copy(pinInput = next, pinError = false) }
        if (next.length == 4) submit(next)
    }
    fun backspace() = _ui.update { it.copy(pinInput = it.pinInput.dropLast(1)) }

    fun fingerprintOk() {
        if (_ui.value.lockVisible) handle(engine.fingerprintVerified(now()))
        else if (_ui.value.pad == PadPurpose.DISARM) { closePad(); handle(engine.disarm(CommandSource.CAR_SCREEN)) }
    }
    fun fingerprintFail() { if (_ui.value.lockVisible) handle(engine.fingerprintFailed(now())) }

    fun setAutoRelock(v: Boolean) { prefs.autoRelock = v; engine.config = engine.config.copy(autoRelock = v); _ui.update { it.copy(autoRelock = v) } }
    fun setShuffle(v: Boolean) { prefs.shuffleKeypad = v; _ui.update { it.copy(shuffleKeypad = v) } }
    fun setFingerprint(v: Boolean) { prefs.useFingerprint = v; _ui.update { it.copy(useFingerprint = v) } }
    fun removeFamily() { prefs.setFamilyPin(null); _ui.update { it.copy(hasFamily = false, toast = "가족 운전자 비밀번호를 삭제했습니다") } }
    fun clearToast() = _ui.update { it.copy(toast = null) }

    // 시연 패널
    fun simulate(change: (VehicleSignals) -> VehicleSignals) = vehicle.simulate(change)
    fun simulateGeofenceExit() = location.simulateExit()

    // ------------------------------------------------------------------ 비밀번호 처리

    private fun submit(pin: String) {
        val ui = _ui.value
        if (ui.lockVisible) {
            handle(engine.enterPin(pin, now()))
            _ui.update { it.copy(pinInput = "") }
            return
        }
        when (ui.pad) {
            PadPurpose.SETUP_1, PadPurpose.CHANGE_NEW, PadPurpose.FAMILY_NEW -> {
                if (PinHasher.isWeak(pin)) return padError("같은 숫자·연속 숫자는 쓸 수 없습니다")
                if (ui.pad == PadPurpose.FAMILY_NEW) {
                    if (prefs.verifyOwner(pin)) return padError("차주 비밀번호와 달라야 합니다")
                    prefs.setFamilyPin(pin); closePad()
                    _ui.update { it.copy(hasFamily = true, toast = "가족 운전자 비밀번호를 등록했습니다") }
                    log("가족 운전자 비밀번호 등록")
                    return
                }
                pendingNewPin = pin
                _ui.update { it.copy(pad = PadPurpose.SETUP_2, pinInput = "", pinMessage = "한 번 더 입력하세요", pinError = false, keypadOrder = keypad()) }
            }
            PadPurpose.SETUP_2 -> {
                if (pin != pendingNewPin) {
                    pendingNewPin = null
                    _ui.update { it.copy(pad = if (prefs.isSetUp) PadPurpose.CHANGE_NEW else PadPurpose.SETUP_1, pinInput = "", pinMessage = "두 번 입력한 번호가 다릅니다. 처음부터 다시", pinError = true) }
                    return
                }
                prefs.setOwnerPin(pin); pendingNewPin = null; closePad()
                _ui.update { it.copy(needsSetup = false, toast = "차주 비밀번호를 저장했습니다") }
                log("차주 비밀번호 등록/변경")
            }
            PadPurpose.CHANGE_VERIFY -> {
                if (!prefs.verifyOwner(pin)) return padError("현재 비밀번호가 맞지 않습니다")
                _ui.update { it.copy(pad = PadPurpose.CHANGE_NEW, pinInput = "", pinMessage = null, keypadOrder = keypad()) }
            }
            PadPurpose.DISARM -> {
                if (!prefs.verifyOwner(pin)) return padError("차주 비밀번호가 아닙니다")
                closePad(); handle(engine.disarm(CommandSource.CAR_SCREEN))
            }
            else -> Unit
        }
    }

    private fun padError(msg: String) = _ui.update { it.copy(pinInput = "", pinMessage = msg, pinError = true) }

    // ------------------------------------------------------------------ 엔진 이벤트 → 화면·차량

    private fun handle(events: List<GuardEvent>) {
        events.forEach { e ->
            when (e) {
                is GuardEvent.ShowLock -> _ui.update {
                    it.copy(lockVisible = true, lockValet = e.valet, pad = null, pinInput = "", pinMessage = null, pinError = false, keypadOrder = keypad())
                }
                GuardEvent.HideLock -> _ui.update { it.copy(lockVisible = false, pinInput = "", pinMessage = null) }
                is GuardEvent.PinRejected -> _ui.update {
                    it.copy(
                        pinError = true, pinInput = "",
                        pinMessage = if (e.locked) "입력이 ${e.remainingSec}초 동안 제한됩니다. 차주에게 알림을 보냈습니다"
                        else "비밀번호가 틀렸습니다 (남은 기회 ${e.remainingTries}회)",
                        keypadOrder = keypad(),
                    )
                }
                is GuardEvent.NotifyOwner -> {
                    val danger = e.alert in setOf(Alert.WRONG_PIN, Alert.UNAUTHORIZED_MOVE, Alert.VALET_RADIUS_EXIT)
                    _ui.update { it.copy(alerts = (listOf(OwnerAlert(time(), e.alert.title, e.message, danger)) + it.alerts).take(30)) }
                }
                is GuardEvent.Speak -> if (ttsReady) tts.speak(e.text, TextToSpeech.QUEUE_FLUSH, null, "keeper")
                is GuardEvent.Log -> log(e.text)
                is GuardEvent.StartGeofence -> location.addGeofence(e.radiusMeters) { handle(engine.onGeofenceExit()) }
                is GuardEvent.ValetReport -> _ui.update {
                    it.copy(valetReport = buildString {
                        append(if (e.drove) "발레 기사가 차량을 운행했습니다. " else "차량 운행 기록이 없습니다. ")
                        append("기어 조작 ${e.gearChanges}회. ")
                        append(if (e.radiusExited) "⚠ 설정 반경을 벗어난 적이 있습니다." else "설정 반경 안에서만 이동했습니다.")
                    })
                }
                GuardEvent.LockDoors -> vehicle.lockAllDoors()
                GuardEvent.ParkedAlarm -> vehicle.setParkedWarningLights(true)
                GuardEvent.StopAlarm -> { vehicle.setParkedWarningLights(false); stopTracking() }
                GuardEvent.StartTracking -> {
                    _ui.update { it.copy(tracking = true) }
                    location.startTracking { p ->
                        val s = "%.5f, %.5f".format(p.lat, p.lng)
                        _ui.update { it.copy(lastLocation = s, alerts = (listOf(OwnerAlert(time(), "차량 위치 전송", s, true)) + it.alerts).take(30)) }
                    }
                }
                GuardEvent.StopTracking -> { stopTracking(); location.removeGeofence() }
            }
        }
        _ui.update { it.copy(guard = engine.state) }
    }

    private fun stopTracking() { location.stopTracking(); _ui.update { it.copy(tracking = false) } }

    private fun keypad(): List<Int> = if (prefs.shuffleKeypad) (0..9).shuffled() else (1..9).toList() + 0

    private fun log(t: String) = _ui.update { it.copy(logs = (listOf(LogItem(time(), t)) + it.logs).take(60)) }
    private fun time() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
    private fun now() = System.currentTimeMillis()

    val isArmed get() = engine.state.mode != GuardMode.OFF
    val phase get() = engine.state.phase

    override fun onCleared() {
        vehicle.stop(); location.release(); tts.shutdown()
    }
}

val Phase.isLockedLike get() = this == Phase.STANDBY || this == Phase.CHALLENGE || this == Phase.LOCKOUT
