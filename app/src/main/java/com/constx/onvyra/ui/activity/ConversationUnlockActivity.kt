package com.constx.onvyra.ui.activity

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Private system-authentication boundary. No chat messages or title are rendered here, and
 * no result is successful without a real biometric or device-credential verification.
 */
class ConversationUnlockActivity : ComponentActivity() {
    private var signal: CancellationSignal? = null
    private var complete = false
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        finishAuthentication(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            finishAuthentication(false)
            return
        }
        // A recreated Activity cannot restore a native prompt callback. Ask again instead of
        // treating its prior in-flight state as successful authentication.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            authenticateModern()
        } else {
            authenticateCredential()
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun authenticateModern() {
        val cancellation = CancellationSignal().also { signal = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("解锁聊天")
            .setSubtitle("请验证指纹、人脸或设备锁屏密码")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        try {
            prompt.authenticate(cancellation, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finishAuthentication(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancellation is final; unavailable biometric hardware may still use
                    // the system's screen-lock confirmation, which performs real verification.
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                        authenticateCredential()
                    } else finishAuthentication(false)
                }
            })
        } catch (_: RuntimeException) {
            authenticateCredential()
        }
    }

    @Suppress("DEPRECATION")
    private fun authenticateCredential() {
        if (complete || isFinishing || isDestroyed) return
        val intent = getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent("解锁聊天", "请验证设备锁屏密码")
        if (intent == null) finishAuthentication(false)
        else runCatching { credential.launch(intent) }.onFailure { finishAuthentication(false) }
    }

    private fun finishAuthentication(success: Boolean) {
        if (complete) return
        complete = true
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        complete = true
        signal?.cancel()
        signal = null
        super.onDestroy()
    }

    companion object { const val EXTRA_TITLE = "conversation_title" }
}
