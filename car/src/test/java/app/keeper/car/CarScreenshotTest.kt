package app.keeper.car

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.keeper.core.Gear
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** 차량용 앱 주요 장면을 PNG로 저장한다 (build/screens). 실제 Compose 화면을 그대로 렌더링. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-hdpi")
class CarScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val out = File("build/screens").apply { mkdirs() }
    private val vm get() = ViewModelProvider(rule.activity)[CarViewModel::class.java]

    private fun idle(ms: Long = 50) { rule.mainClock.advanceTimeBy(ms); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); rule.waitForIdle() }
    private fun shot(name: String) {
        idle(400)
        val bmp = captureAll()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun type(pin: String) { pin.forEach { vm.digit(it - '0'); idle() } }

    @Suppress("UNCHECKED_CAST")
    private fun captureAll(): Bitmap {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val wmg = wmgClass.getMethod("getInstance").invoke(null)
        val views = (wmgClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<android.view.View>).toList()
        val main = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        main.draw(c)
        views.filter { it !== main && it.width > 0 && it.isShown }.forEach { v ->
            c.drawColor(0x99000000.toInt())
            c.save()
            c.translate(((main.width - v.width) / 2f).coerceAtLeast(0f), ((main.height - v.height) / 2f).coerceAtLeast(0f))
            v.draw(c)
            c.restore()
        }
        return bmp
    }

    @Test fun scenes() {
        rule.mainClock.autoAdvance = false
        idle()
        shot("car_00_setup")
        type("2580"); type("2580")
        idle(2500)
        shot("car_01_off")

        vm.armFromCar(); idle()
        shot("car_02_armed")

        vm.simulate { it.copy(driverSeated = true, brakePressed = true) }; idle()
        shot("car_03_lock")

        type("1357"); type("9134"); idle()
        shot("car_04_wrong_once")
        type("4680")
        shot("car_05_lockout")

        vm.simulate { it.copy(gear = Gear.DRIVE) }; idle()
        vm.closePad()
        shot("car_06_alarm")

        // 차주가 지문으로 인증 → 경보 해제
        vm.fingerprintOk(); idle()
        shot("car_07_unlocked")

        // 주차 후 하차 → 자동 재잠금
        vm.simulate { it.copy(gear = Gear.PARK) }; idle()
        vm.simulate { it.copy(driverSeated = false, brakePressed = false) }; idle()
        shot("car_08_relocked")

        // 발레 모드
        vm.openPad(PadPurpose.DISARM); type("2580"); idle()
        vm.startValet(180, 1000); idle()
        shot("car_09_valet_pin")
        vm.dismissValetPin(); idle()
        vm.simulate { it.copy(driverSeated = true) }; idle()
        shot("car_10_valet_lock")
    }
}
