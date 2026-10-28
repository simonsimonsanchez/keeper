package app.keeper.phone

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-port-xhdpi")
class PhoneScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("build/screens").apply { mkdirs() }
    private val vm get() = ViewModelProvider(rule.activity)[PhoneViewModel::class.java]

    private fun idle(ms: Long = 50) { rule.mainClock.advanceTimeBy(ms); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); rule.waitForIdle() }
    private fun shot(name: String) {
        idle(300)
        val bmp = captureAll()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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
        shot("phone_01_home_off")
        vm.arm(); idle(1200); idle(2500)
        shot("phone_02_home_on")
        rule.onNodeWithText("발레").performClick(); idle()
        shot("phone_03_valet_setup")
        vm.startValet(180, 1000); idle(1200); idle(2500)
        vm.demoValetFlow(); idle(3000)
        shot("phone_04_valet_active")
        rule.onNodeWithText("홈").performClick(); idle()
        vm.demoWrongPin(); idle()
        vm.demoUnauthorizedMove(); idle(1600 * 12)
        shot("phone_05_home_alert")
        rule.onNodeWithText("보기 ›").performClick(); idle()
        shot("phone_06_alert_map")
        rule.onNodeWithText("‹ 알림 목록").performClick(); idle()
        shot("phone_07_alert_list")
    }
}
