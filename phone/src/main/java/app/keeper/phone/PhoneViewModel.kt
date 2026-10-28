package app.keeper.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keeper.core.Alert
import app.keeper.core.PinHasher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneAlert(
    val id: Long, val time: String, val alert: Alert, val message: String,
    val track: List<Pair<Double, Double>> = emptyList(), val read: Boolean = false,
) {
    val danger get() = alert in setOf(Alert.WRONG_PIN, Alert.UNAUTHORIZED_MOVE, Alert.VALET_RADIUS_EXIT)
}

data class Valet(val pin: String, val minutes: Int, val radius: Int, val startedAt: Long, val driving: Boolean = false, val gearChanges: Int = 0, val exited: Boolean = false)

data class FamilyMember(val name: String, val enabled: Boolean)

data class PhoneUi(
    val carName: String = "디 올 뉴 아반떼",
    val plate: String = "12가 3456",
    val armed: Boolean = false,
    val pendingArm: Boolean = false,
    val doorsLocked: Boolean = true,
    val parked: Boolean = true,
    val sending: Boolean = false,
    val valet: Valet? = null,
    val valetReport: String? = null,
    val alerts: List<PhoneAlert> = emptyList(),
    val family: List<FamilyMember> = listOf(FamilyMember("배우자", true)),
    val autoArmOnLeave: Boolean = true,
    val notifyFamily: Boolean = true,
    val lastSync: String = "방금 전",
    val toast: String? = null,
)

/**
 * 스마트폰 앱 상태.
 * 실서비스에서는 현대차 커넥티드 카 서버(블루링크/마이현대 계열)를 거쳐 차량의 KEEPER 앱과 명령·알림을 주고받는다.
 * 공모전 시연 버전은 [MockCarLink]가 차량 응답과 이벤트를 흉내 낸다.
 */
class PhoneViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PhoneUi())
    val ui: StateFlow<PhoneUi> = _ui
    private var demoJob: Job? = null
    private var nextId = 1L

    init { createChannel() }

    // ------------------------------------------------------------- 원격 명령

    fun arm() = send("도난방지모드를 켰습니다") {
        if (it.parked) it.copy(armed = true, pendingArm = false, doorsLocked = true)
        else it.copy(armed = true, pendingArm = true)   // 주행 중: 다음 주차 때 적용
    }

    /** 본인 인증(지문/화면잠금) 성공 후에만 호출. */
    fun disarm() = send("도난방지모드를 해제했습니다") { it.copy(armed = false, pendingArm = false, valet = null) }

    fun startValet(minutes: Int, radius: Int) {
        val pin = PinHasher.randomPin()
        send("발레 임시 비밀번호를 발급했습니다") {
            it.copy(armed = true, valet = Valet(pin, minutes, radius, System.currentTimeMillis()), valetReport = null)
        }
    }

    fun endValet() {
        val v = _ui.value.valet ?: return
        val report = buildString {
            append(if (v.driving || v.gearChanges > 0) "발레 기사가 차량을 운행했습니다.\n" else "운행 기록이 없습니다.\n")
            append("기어 조작 ${v.gearChanges}회\n")
            append(if (v.exited) "⚠ 설정 반경(${v.radius}m)을 벗어난 적이 있습니다." else "설정 반경(${v.radius}m) 안에서만 이동했습니다.")
        }
        send("발레를 끝내고 출발 잠금으로 전환했습니다") { it.copy(valet = null, valetReport = report, armed = true) }
    }

    fun dismissReport() = _ui.update { it.copy(valetReport = null) }
    fun setAutoArm(v: Boolean) = _ui.update { it.copy(autoArmOnLeave = v) }
    fun setNotifyFamily(v: Boolean) = _ui.update { it.copy(notifyFamily = v) }
    fun toggleFamily(i: Int) = _ui.update { s -> s.copy(family = s.family.mapIndexed { idx, f -> if (idx == i) f.copy(enabled = !f.enabled) else f }) }
    fun markRead(id: Long) = _ui.update { s -> s.copy(alerts = s.alerts.map { if (it.id == id) it.copy(read = true) else it }) }
    fun clearToast() = _ui.update { it.copy(toast = null) }

    private fun send(done: String, change: (PhoneUi) -> PhoneUi) {
        viewModelScope.launch {
            _ui.update { it.copy(sending = true) }
            delay(900) // 서버 → 차량 전달 시간 흉내
            _ui.update { change(it).copy(sending = false, toast = done, lastSync = time()) }
        }
    }

    // ------------------------------------------------------------- 차량에서 오는 이벤트 (시연)

    fun demoWrongPin() = push(Alert.WRONG_PIN, "비밀번호가 3회 틀렸습니다. 차량 침입이 의심됩니다. (서울 중구 세종대로 주차장)")

    fun demoUnauthorizedMove() {
        demoJob?.cancel()
        val id = push(Alert.UNAUTHORIZED_MOVE, "인증 없이 기어가 D로 변경되었습니다. 무단 운행 의심 — 실시간 위치를 받는 중", listOf(37.5665 to 126.9780))
        _ui.update { it.copy(parked = false) }
        demoJob = viewModelScope.launch {
            repeat(12) { step ->
                delay(1500)
                _ui.update { s ->
                    s.copy(alerts = s.alerts.map { a ->
                        if (a.id == id) a.copy(track = a.track + ((37.5665 + (step + 1) * 0.0009) to (126.9780 + (step + 1) * 0.0012 + if (step > 6) 0.0006 else 0.0)))
                        else a
                    })
                }
            }
        }
    }

    fun demoValetFlow() {
        val v = _ui.value.valet ?: return
        viewModelScope.launch {
            push(Alert.VALET_STARTED, "발레 기사가 임시 비밀번호로 운행을 시작했습니다")
            _ui.update { s -> s.copy(valet = s.valet?.copy(driving = true, gearChanges = 4), parked = false) }
            delay(2500)
            push(Alert.VALET_RADIUS_EXIT, "발레 차량이 설정 반경(${v.radius}m)을 벗어났습니다")
            _ui.update { s -> s.copy(valet = s.valet?.copy(exited = true, gearChanges = 6)) }
        }
    }

    fun stopDemo() { demoJob?.cancel(); _ui.update { it.copy(parked = true) } }

    private fun push(alert: Alert, message: String, track: List<Pair<Double, Double>> = emptyList()): Long {
        val id = nextId++
        _ui.update { it.copy(alerts = listOf(PhoneAlert(id, time(), alert, message, track)) + it.alerts) }
        notify(id.toInt(), alert, message)
        return id
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "차량 보안 알림", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notify(id: Int, alert: Alert, message: String) {
        val ctx = getApplication<Application>()
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_keeper)
            .setContentTitle("KEEPER · ${alert.title}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n) }
    }

    private fun time() = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

    companion object { private const val CH = "keeper_security" }
}
