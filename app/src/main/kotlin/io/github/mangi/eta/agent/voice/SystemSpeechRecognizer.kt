package io.github.mangi.eta.agent.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

internal object SystemSpeechRecognizer {
    fun create(context: Context): SpeechRecognizer? {
        val appContext = context.applicationContext
        resolveExternalService(appContext)?.let { component ->
            return SpeechRecognizer.createSpeechRecognizer(appContext, component)
        }
        return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            null
        }
    }

    internal fun resolveExternalService(context: Context): ComponentName? {
        val configured = Settings.Secure.getString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE,
        )?.let(ComponentName::unflattenFromString)
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        return services
            .asSequence()
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .filter { it.permission == "android.permission.BIND_SPEECH_RECOGNITION_SERVICE" }
            .sortedWith(
                compareByDescending<android.content.pm.ServiceInfo> {
                    ComponentName(it.packageName, it.name) == configured
                }.thenByDescending {
                    (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                },
            )
            .map { ComponentName(it.packageName, it.name) }
            .firstOrNull()
    }

    private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
}
