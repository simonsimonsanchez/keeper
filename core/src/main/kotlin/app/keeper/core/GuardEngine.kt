package app.keeper.core

/**
 * KEEPER 출발 잠금 판단 엔진.
 *
 * 차량 신호(운전석 착석·브레이크·기어)와 사용자 입력(PIN·지문·원격 명령)을 받아
 * "지금 잠금 화면을 띄울지 / 경보를 낼지 / 통과시킬지"를 결정한다.
 * Android·Pleos SDK에 의존하지 않는 순수 Kotlin 코드라 단위 테스트로 검증한다.
 *
 * 안전 원칙
 *  1. 주행 중(P 이외 기어)에는 절대로 새로 잠그지 않는다. 원격으로 켜도 "다음 주차 시 적용"으로 보류한다.
 *  2. 무단 출발이 감지돼도 차량 동작을 방해하는 제어(조명 점멸 등)는 하지 않는다.
 *     주행 중에는 차주 알림 + 위치 추적 + 음성 안내만 한다.
 *  3. 비밀번호 3회 오류 시 일정 시간 입력을 막고 차주에게 즉시 알린다.
 */
class GuardEngine(
    private val pins: PinStore,
    var config: GuardConfig = GuardConfig(),
) {
    var state: GuardState = GuardState()
        private set

    private var lastSignals = VehicleSignals()

    // ---------------------------------------------------------------- 원격·사용자 명령

    /** 도난방지모드 켜기 (차량 화면 또는 스마트폰 원격). */
    fun arm(now: Long, source: CommandSource): List<GuardEvent> {
        val events = mutableListOf<GuardEvent>(GuardEvent.Log("도난방지모드 켜기 요청 (${source.label})"))
        if (lastSignals.isMoving) {
            state = state.copy(mode = GuardMode.ARMED, pendingArm = true)
            events += GuardEvent.Log("주행 중이라 다음 주차(P단·운전석 하차) 때 적용됩니다")
            events += GuardEvent.Speak("도난 방지 모드는 주차 후에 켜집니다.")
            return events
        }
        if (source == CommandSource.CAR_SCREEN && lastSignals.driverSeated) {
            // 차 안에서 켠 경우: 차주가 내리는 순간부터 잠금
            state = state.copy(mode = GuardMode.ARMED, pendingArm = true, valet = null, failCount = 0)
            events += GuardEvent.Log("하차하면 출발 잠금이 켜집니다")
            events += GuardEvent.Speak("내리시면 출발 잠금이 켜집니다.")
            return events
        }
        state = state.copy(mode = GuardMode.ARMED, pendingArm = false, valet = null, failCount = 0)
        events += GuardEvent.LockDoors
        events += enterStandbyOrChallenge(now)
        return events
    }

    /** 도난방지모드 끄기. 스마트폰에서 차주 본인 인증 후에만 호출된다. */
    fun disarm(source: CommandSource): List<GuardEvent> {
        state = GuardState()
        return listOf(
            GuardEvent.HideLock,
            GuardEvent.StopAlarm,
            GuardEvent.Log("도난방지모드 해제 (${source.label})"),
        )
    }

    /** 발레(주차대행) 모드 시작: 1회용 임시 PIN, 유효시간, 이동 반경 제한. */
    fun startValet(now: Long, tempPin: String, minutes: Int, radiusMeters: Int, source: CommandSource): List<GuardEvent> {
        require(tempPin.length == 4 && tempPin.all(Char::isDigit)) { "임시 PIN은 숫자 4자리" }
        val valet = ValetSession(
            pinHash = PinHasher.hash(tempPin, pins.salt),
            expiresAt = now + minutes * 60_000L,
            radiusMeters = radiusMeters,
        )
        state = state.copy(mode = GuardMode.VALET, valet = valet, pendingArm = false, failCount = 0)
        return listOf(
            GuardEvent.Log("발레 모드 시작 — 임시 비밀번호 발급, ${minutes}분 유효, 반경 ${radiusMeters}m (${source.label})"),
        ) + enterStandbyOrChallenge(now)
    }

    fun endValet(now: Long, source: CommandSource): List<GuardEvent> {
        val report = state.valet
        val events = mutableListOf<GuardEvent>()
        if (report != null) {
            events += GuardEvent.ValetReport(report.gearChanges, report.radiusExited, report.startedDrivingAt != null)
        }
        events += GuardEvent.StopTracking
        events += arm(now, source)
        return events
    }

    // ---------------------------------------------------------------- 인증

    fun enterPin(pin: String, now: Long): List<GuardEvent> {
        if (state.phase !in setOf(Phase.CHALLENGE, Phase.ALARM, Phase.LOCKOUT)) return emptyList()
        if (state.lockoutUntil > now) {
            return listOf(GuardEvent.PinRejected(remainingSec = ((state.lockoutUntil - now) / 1000).toInt(), locked = true))
        }
        val driver = matchPin(pin, now)
        return if (driver != null) unlock(driver, now, "비밀번호") else wrongPin(now)
    }

    fun fingerprintVerified(now: Long): List<GuardEvent> {
        if (state.phase !in setOf(Phase.CHALLENGE, Phase.ALARM, Phase.LOCKOUT)) return emptyList()
        return unlock(Driver.OWNER, now, "지문")
    }

    fun fingerprintFailed(now: Long): List<GuardEvent> = wrongPin(now)

    // ---------------------------------------------------------------- 차량 신호

    fun onSignals(s: VehicleSignals, now: Long): List<GuardEvent> {
        val prev = lastSignals
        lastSignals = s
        val events = mutableListOf<GuardEvent>()

        // 기어 변화 기록 (발레 리포트용)
        if (prev.gear != s.gear && state.mode == GuardMode.VALET && state.phase == Phase.UNLOCKED) {
            state.valet?.let { v ->
                state = state.copy(valet = v.copy(
                    gearChanges = v.gearChanges + 1,
                    startedDrivingAt = v.startedDrivingAt ?: if (s.isMoving) now else null,
                ))
            }
        }

        // 보류된 원격 잠금: 주차 + 하차 시 적용
        if (state.pendingArm && s.gear == Gear.PARK && !s.driverSeated) {
            state = state.copy(pendingArm = false, sessionDriver = null)
            events += GuardEvent.Log("보류했던 도난방지모드를 주차 확인 후 적용했습니다")
            events += GuardEvent.LockDoors
            events += enterStandbyOrChallenge(now)
            return events
        }

        when (state.phase) {
            Phase.IDLE -> Unit

            Phase.STANDBY -> when {
                s.isMoving -> events += unauthorizedMove(now)
                s.driverSeated || s.brakePressed -> {
                    state = state.copy(phase = Phase.CHALLENGE)
                    events += GuardEvent.ShowLock(state.mode == GuardMode.VALET)
                    events += GuardEvent.Speak(
                        if (state.mode == GuardMode.VALET) "발레 모드입니다. 전달받은 임시 비밀번호를 입력하세요."
                        else "출발 잠금 중입니다. 비밀번호 또는 지문으로 인증하세요."
                    )
                    events += GuardEvent.Log("운전석 착석/브레이크 감지 → 인증 요청")
                }
            }

            Phase.CHALLENGE, Phase.LOCKOUT -> when {
                s.isMoving -> events += unauthorizedMove(now)
                !s.driverSeated && !s.brakePressed && s.gear == Gear.PARK && state.phase == Phase.CHALLENGE -> {
                    state = state.copy(phase = Phase.STANDBY)
                    events += GuardEvent.HideLock
                    events += GuardEvent.Log("운전석 하차 — 대기 상태로 복귀")
                }
            }

            Phase.ALARM -> Unit // 인증 또는 원격 해제로만 빠져나온다

            Phase.UNLOCKED -> {
                // 인증된 주행 세션 종료: P단 + 운전석 하차
                val sessionEnded = s.gear == Gear.PARK && !s.driverSeated && prev.driverSeated
                if (sessionEnded && config.autoRelock && state.mode == GuardMode.ARMED) {
                    state = state.copy(sessionDriver = null)
                    events += GuardEvent.Log("하차 감지 — 출발 잠금 다시 켬")
                    events += enterStandbyOrChallenge(now)
                }
                if (sessionEnded && state.mode == GuardMode.VALET) {
                    // 발레 기사는 여러 번 타고 내릴 수 있다. 유효시간 동안은 다시 임시 PIN을 요구한다.
                    state = state.copy(sessionDriver = null)
                    events += enterStandbyOrChallenge(now)
                }
            }
        }
        return events
    }

    /** 1초마다 호출: 입력 제한 해제, 발레 유효시간 확인. */
    fun tick(now: Long): List<GuardEvent> {
        val events = mutableListOf<GuardEvent>()
        if (state.phase == Phase.LOCKOUT && state.lockoutUntil in 1..now) {
            state = state.copy(phase = Phase.CHALLENGE, lockoutUntil = 0)
            events += GuardEvent.Log("입력 제한 해제")
        }
        val v = state.valet
        if (state.mode == GuardMode.VALET && v != null && !v.expiredNotified && now >= v.expiresAt) {
            state = state.copy(valet = v.copy(expiredNotified = true))
            events += GuardEvent.NotifyOwner(Alert.VALET_EXPIRED, "발레 임시 비밀번호 유효시간이 끝났습니다")
            if (!lastSignals.isMoving) {
                // 주차 중이면 즉시 일반 도난방지모드로 전환. 주행 중이면 다음 주차 때 전환.
                events += endValet(now, CommandSource.SYSTEM)
            } else {
                state = state.copy(pendingArm = true)
            }
        }
        return events
    }

    /** 발레 반경 이탈 (Pleos FusedLocation ProximityAlert EXIT). */
    fun onGeofenceExit(): List<GuardEvent> {
        val v = state.valet ?: return emptyList()
        if (v.radiusExited) return emptyList()
        state = state.copy(valet = v.copy(radiusExited = true))
        return listOf(GuardEvent.NotifyOwner(Alert.VALET_RADIUS_EXIT, "발레 차량이 설정 반경(${v.radiusMeters}m)을 벗어났습니다"))
    }

    // ---------------------------------------------------------------- 내부

    private fun enterStandbyOrChallenge(now: Long): List<GuardEvent> {
        val s = lastSignals
        return if (s.driverSeated || s.brakePressed) {
            state = state.copy(phase = Phase.CHALLENGE)
            listOf(GuardEvent.ShowLock(state.mode == GuardMode.VALET))
        } else {
            state = state.copy(phase = Phase.STANDBY)
            listOf(GuardEvent.HideLock, GuardEvent.Log("출발 잠금 대기 중"))
        }
    }

    private fun matchPin(pin: String, now: Long): Driver? {
        val hash = PinHasher.hash(pin, pins.salt)
        if (hash == pins.ownerHash) return Driver.OWNER
        if (pins.familyHashes.contains(hash)) return Driver.FAMILY
        val v = state.valet
        if (state.mode == GuardMode.VALET && v != null && hash == v.pinHash && now < v.expiresAt) return Driver.VALET
        return null
    }

    private fun unlock(driver: Driver, now: Long, method: String): List<GuardEvent> {
        val wasAlarm = state.phase == Phase.ALARM
        state = state.copy(phase = Phase.UNLOCKED, failCount = 0, lockoutUntil = 0, sessionDriver = driver)
        val events = mutableListOf<GuardEvent>(
            GuardEvent.HideLock,
            GuardEvent.StopAlarm,
            GuardEvent.Log("${driver.label} 인증 성공 ($method)"),
            GuardEvent.Speak(if (driver == Driver.VALET) "인증되었습니다. 안전 운전하세요." else "${driver.label}님, 인증되었습니다."),
        )
        if (driver == Driver.VALET) {
            state.valet?.let { v -> events += GuardEvent.StartGeofence(v.radiusMeters) }
            events += GuardEvent.NotifyOwner(Alert.VALET_STARTED, "발레 기사가 임시 비밀번호로 운행을 시작했습니다")
        }
        if (driver == Driver.FAMILY) {
            events += GuardEvent.NotifyOwner(Alert.FAMILY_STARTED, "가족 운전자가 차량을 사용합니다")
        }
        if (wasAlarm) events += GuardEvent.NotifyOwner(Alert.ALARM_CLEARED, "경보가 차량에서 인증으로 해제되었습니다")
        return events
    }

    private fun wrongPin(now: Long): List<GuardEvent> {
        val fails = state.failCount + 1
        val events = mutableListOf<GuardEvent>()
        if (fails >= config.maxFails) {
            val lockMs = config.lockoutBaseSec * 1000L * (1 shl (state.lockoutCount.coerceAtMost(4)))
            state = state.copy(
                phase = Phase.LOCKOUT, failCount = 0, lockoutUntil = now + lockMs,
                lockoutCount = state.lockoutCount + 1,
            )
            events += GuardEvent.PinRejected(remainingSec = (lockMs / 1000).toInt(), locked = true)
            events += GuardEvent.NotifyOwner(Alert.WRONG_PIN, "비밀번호가 ${config.maxFails}회 틀렸습니다. 차량 침입이 의심됩니다")
            events += GuardEvent.ParkedAlarm   // 주차 상태에서만 실내등·앰비언트 경고
            events += GuardEvent.Speak("비밀번호가 여러 번 틀렸습니다. 차주에게 알림을 보냈습니다.")
            events += GuardEvent.Log("비밀번호 ${config.maxFails}회 오류 → ${lockMs / 1000}초 입력 제한, 차주 알림")
        } else {
            state = state.copy(failCount = fails)
            events += GuardEvent.PinRejected(remainingTries = config.maxFails - fails)
            events += GuardEvent.Log("비밀번호 오류 ${fails}회")
        }
        return events
    }

    private fun unauthorizedMove(now: Long): List<GuardEvent> {
        state = state.copy(phase = Phase.ALARM)
        return listOf(
            GuardEvent.NotifyOwner(Alert.UNAUTHORIZED_MOVE, "인증 없이 기어가 변경되었습니다. 무단 운행 의심"),
            GuardEvent.StartTracking,
            GuardEvent.Speak("인증되지 않은 운행입니다. 차량 위치가 차주에게 전송됩니다."),
            GuardEvent.Log("⚠ 인증 없이 기어 변경 → 차주 알림 + 위치 추적 시작 (주행 방해 제어 없음)"),
        )
    }
}

// =================================================================== 모델

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

data class VehicleSignals(
    val driverSeated: Boolean = false,
    val brakePressed: Boolean = false,
    val gear: Gear = Gear.PARK,
    val doorsLocked: Boolean = true,
    val speedKmh: Float = 0f,
) {
    val isMoving: Boolean get() = gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL || speedKmh > 3f
}

enum class GuardMode { OFF, ARMED, VALET }

enum class Phase { IDLE, STANDBY, CHALLENGE, LOCKOUT, ALARM, UNLOCKED }

enum class Driver(val label: String) { OWNER("차주"), FAMILY("가족"), VALET("발레 기사") }

enum class CommandSource(val label: String) { CAR_SCREEN("차량 화면"), PHONE("스마트폰 앱"), SYSTEM("자동") }

enum class Alert(val title: String) {
    WRONG_PIN("비밀번호 반복 오류"),
    UNAUTHORIZED_MOVE("무단 운행 의심"),
    VALET_STARTED("발레 운행 시작"),
    VALET_RADIUS_EXIT("발레 반경 이탈"),
    VALET_EXPIRED("발레 시간 종료"),
    FAMILY_STARTED("가족 운전 시작"),
    ALARM_CLEARED("경보 해제"),
}

data class ValetSession(
    val pinHash: String,
    val expiresAt: Long,
    val radiusMeters: Int,
    val gearChanges: Int = 0,
    val radiusExited: Boolean = false,
    val startedDrivingAt: Long? = null,
    val expiredNotified: Boolean = false,
)

data class GuardState(
    val mode: GuardMode = GuardMode.OFF,
    val phase: Phase = Phase.IDLE,
    val failCount: Int = 0,
    val lockoutUntil: Long = 0,
    val lockoutCount: Int = 0,
    val pendingArm: Boolean = false,
    val valet: ValetSession? = null,
    val sessionDriver: Driver? = null,
)

data class GuardConfig(
    val maxFails: Int = 3,
    val lockoutBaseSec: Int = 30,
    val autoRelock: Boolean = true,
)

sealed interface GuardEvent {
    data class ShowLock(val valet: Boolean) : GuardEvent
    data object HideLock : GuardEvent
    data class PinRejected(val remainingTries: Int = 0, val remainingSec: Int = 0, val locked: Boolean = false) : GuardEvent
    data class NotifyOwner(val alert: Alert, val message: String) : GuardEvent
    data class Speak(val text: String) : GuardEvent
    data class Log(val text: String) : GuardEvent
    data class StartGeofence(val radiusMeters: Int) : GuardEvent
    data class ValetReport(val gearChanges: Int, val radiusExited: Boolean, val drove: Boolean) : GuardEvent
    data object LockDoors : GuardEvent
    data object ParkedAlarm : GuardEvent
    data object StopAlarm : GuardEvent
    data object StartTracking : GuardEvent
    data object StopTracking : GuardEvent
}
