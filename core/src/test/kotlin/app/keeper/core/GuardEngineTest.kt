package app.keeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardEngineTest {
    private val pins = MemoryPinStore(ownerPin = "2580", familyPins = listOf("3691"), salt = "test")
    private val parkedEmpty = VehicleSignals(driverSeated = false, brakePressed = false, gear = Gear.PARK)
    private val seated = parkedEmpty.copy(driverSeated = true)
    private val seatedBrake = seated.copy(brakePressed = true)
    private val drive = seatedBrake.copy(gear = Gear.DRIVE)

    private fun engine() = GuardEngine(pins).also { it.onSignals(parkedEmpty, 0) }

    @Test fun `잠금 켜고 운전석에 앉으면 인증 화면이 뜬다`() {
        val e = engine()
        e.arm(0, CommandSource.PHONE)
        assertEquals(Phase.STANDBY, e.state.phase)
        val ev = e.onSignals(seated, 1)
        assertEquals(Phase.CHALLENGE, e.state.phase)
        assertTrue(ev.any { it is GuardEvent.ShowLock })
    }

    @Test fun `차주 비밀번호로 해제된다`() {
        val e = engine(); e.arm(0, CommandSource.PHONE); e.onSignals(seated, 1)
        e.enterPin("2580", 2)
        assertEquals(Phase.UNLOCKED, e.state.phase)
        assertEquals(Driver.OWNER, e.state.sessionDriver)
    }

    @Test fun `가족 비밀번호는 가족으로 해제되고 차주에게 알린다`() {
        val e = engine(); e.arm(0, CommandSource.PHONE); e.onSignals(seated, 1)
        val ev = e.enterPin("3691", 2)
        assertEquals(Driver.FAMILY, e.state.sessionDriver)
        assertTrue(ev.any { it is GuardEvent.NotifyOwner && it.alert == Alert.FAMILY_STARTED })
    }

    @Test fun `3회 오류 시 입력 제한과 차주 알림`() {
        val e = engine(); e.arm(0, CommandSource.PHONE); e.onSignals(seated, 1)
        e.enterPin("0000", 2); e.enterPin("1111", 3)
        val ev = e.enterPin("2222", 4)
        assertEquals(Phase.LOCKOUT, e.state.phase)
        assertTrue(ev.any { it is GuardEvent.NotifyOwner && it.alert == Alert.WRONG_PIN })
        // 제한 시간 중에는 맞는 비밀번호도 거부
        val ev2 = e.enterPin("2580", 5)
        assertTrue(ev2.single() is GuardEvent.PinRejected)
        // 30초 지나면 다시 입력 가능
        e.tick(4 + 30_000)
        assertEquals(Phase.CHALLENGE, e.state.phase)
        e.enterPin("2580", 4 + 30_001)
        assertEquals(Phase.UNLOCKED, e.state.phase)
    }

    @Test fun `주행 중 원격으로 켜면 즉시 잠그지 않고 주차 후 적용`() {
        val e = engine()
        e.onSignals(drive, 1)
        e.arm(2, CommandSource.PHONE)
        assertEquals(Phase.IDLE, e.state.phase)
        assertTrue(e.state.pendingArm)
        e.onSignals(seatedBrake, 3)            // P단으로 정차, 아직 앉아 있음
        assertEquals(Phase.IDLE, e.state.phase)
        e.onSignals(parkedEmpty, 4)            // 하차
        assertEquals(Phase.STANDBY, e.state.phase)
        assertFalse(e.state.pendingArm)
    }

    @Test fun `인증 없이 기어를 바꾸면 경보와 위치 추적`() {
        val e = engine(); e.arm(0, CommandSource.PHONE); e.onSignals(seatedBrake, 1)
        val ev = e.onSignals(drive, 2)
        assertEquals(Phase.ALARM, e.state.phase)
        assertTrue(ev.any { it is GuardEvent.NotifyOwner && it.alert == Alert.UNAUTHORIZED_MOVE })
        assertTrue(ev.contains(GuardEvent.StartTracking))
        // 주행 중 경보에서는 조명 경고(ParkedAlarm)를 내지 않는다
        assertFalse(ev.contains(GuardEvent.ParkedAlarm))
    }

    @Test fun `하차하면 자동으로 다시 잠긴다`() {
        val e = engine(); e.arm(0, CommandSource.PHONE); e.onSignals(seated, 1); e.enterPin("2580", 2)
        e.onSignals(drive, 3); e.onSignals(seatedBrake, 4)
        assertEquals(Phase.UNLOCKED, e.state.phase)
        e.onSignals(parkedEmpty, 5)
        assertEquals(Phase.STANDBY, e.state.phase)
    }

    @Test fun `발레 임시 비밀번호 - 시간 내 허용, 시간 지나면 거부`() {
        val e = engine()
        e.startValet(0, "4826", minutes = 60, radiusMeters = 1000, source = CommandSource.PHONE)
        e.onSignals(seated, 1)
        val ev = e.enterPin("4826", 2)
        assertEquals(Driver.VALET, e.state.sessionDriver)
        assertTrue(ev.any { it is GuardEvent.StartGeofence })
        // 하차 후 시간이 지나면 같은 임시 비밀번호는 거부
        e.onSignals(parkedEmpty, 3)
        e.tick(61 * 60_000L)
        assertEquals(GuardMode.ARMED, e.state.mode)
        e.onSignals(seated, 61 * 60_000L + 1)
        e.enterPin("4826", 61 * 60_000L + 2)
        assertTrue(e.state.phase != Phase.UNLOCKED)
    }

    @Test fun `발레 반경 이탈은 한 번만 알린다`() {
        val e = engine()
        e.startValet(0, "4826", 60, 500, CommandSource.PHONE)
        assertEquals(1, e.onGeofenceExit().size)
        assertEquals(0, e.onGeofenceExit().size)
    }

    @Test fun `차 안에서 켜면 내릴 때부터 잠긴다`() {
        val e = engine(); e.onSignals(seated, 1)
        e.arm(2, CommandSource.CAR_SCREEN)
        assertEquals(Phase.IDLE, e.state.phase)
        e.onSignals(parkedEmpty, 3)
        assertEquals(Phase.STANDBY, e.state.phase)
    }

    @Test fun `쉬운 비밀번호 판별`() {
        assertTrue(PinHasher.isWeak("1111")); assertTrue(PinHasher.isWeak("1234")); assertTrue(PinHasher.isWeak("4321"))
        assertFalse(PinHasher.isWeak("2580"))
        repeat(50) { assertFalse(PinHasher.isWeak(PinHasher.randomPin())) }
    }
}
