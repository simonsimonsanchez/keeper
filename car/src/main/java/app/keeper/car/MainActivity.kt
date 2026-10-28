package app.keeper.car

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.keeper.car.ui.CarApp

class MainActivity : FragmentActivity() {
    private val vm: CarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CarApp(vm, onFingerprint = ::authenticateFingerprint) }
    }

    /**
     * 지문 인증.
     * - 지문 센서가 등록된 기기: Android BiometricPrompt
     * - 시연 모드(센서 없음): 센서 터치를 성공으로 처리
     * - 실제 차량에 센서 없음: 안내만 표시 (현대차 지문인증 시동 시스템 연동은 2단계 과제)
     */
    private fun authenticateFingerprint() {
        val canAuth = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = vm.fingerprintOk()
                override fun onAuthenticationFailed() = vm.fingerprintFail()
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("KEEPER 지문 인증")
                    .setSubtitle("등록된 차주 지문으로 출발 잠금을 해제합니다")
                    .setNegativeButtonText("비밀번호 입력")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()
            )
        } else if (vm.vehicle.isSimulated) {
            Toast.makeText(this, "시연: 지문 센서 터치 → 등록된 지문 확인", Toast.LENGTH_SHORT).show()
            vm.fingerprintOk()
        } else {
            Toast.makeText(this, "이 차량에는 지문 센서가 없습니다. 비밀번호를 입력하세요.", Toast.LENGTH_LONG).show()
        }
    }
}
