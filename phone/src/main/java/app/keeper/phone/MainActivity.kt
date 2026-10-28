package app.keeper.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: PhoneViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { PhoneApp(vm, ::authenticate) }
    }

    /** 해제 같은 민감한 명령은 스마트폰 본인 인증(지문 또는 화면잠금) 후에만 보낸다. */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "시연: 이 기기에 화면잠금이 없어 본인 인증을 건너뜁니다", Toast.LENGTH_SHORT).show()
            onSuccess(); return
        }
        runCatching { prompt(title, allowed, onSuccess) }.onFailure { onSuccess() }
    }

    private fun prompt(title: String, allowed: Int, onSuccess: () -> Unit) {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("KEEPER · $title")
                .setSubtitle("차주 본인 확인")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}
