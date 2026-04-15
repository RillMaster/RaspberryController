package com.example.raspberrycontroller

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {

    /**
     * Vérifie si la biométrie (ou code PIN en fallback) est disponible sur l'appareil.
     */
    fun isAvailable(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        return when (manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Affiche la boîte de dialogue d'authentification biométrique.
     * Fallback automatique sur le code PIN / schéma si la biométrie échoue.
     *
     * @param activity    L'activité hôte (doit être un FragmentActivity)
     * @param onSuccess   Appelé si l'authentification réussit
     * @param onError     Appelé avec un message d'erreur (hors annulation volontaire)
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // On ignore les annulations volontaires de l'utilisateur
                val isCancellation = errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        || errorCode == BiometricPrompt.ERROR_CANCELED
                if (!isCancellation) {
                    onError("Erreur biométrique ($errorCode) : $errString")
                }
            }

            override fun onAuthenticationFailed() {
                // Le système affiche déjà son propre retour visuel — on ne fait rien ici
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("RaspberryController")
            .setSubtitle("Vérifiez votre identité pour accéder à l'application")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}