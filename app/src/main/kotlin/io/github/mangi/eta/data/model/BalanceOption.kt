package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val preset: String = PRESET_CUSTOM,
    val apiPath: String = "",
    val resultPath: String = "",
    val userId: String = "",
    val accessToken: String = "",
) {
    companion object {
        const val PRESET_CUSTOM = "custom"
        const val PRESET_NEW_API = "new_api"

        const val NEW_API_PATH = "api/user/self"
        const val NEW_API_RESULT_PATH = "data.quota / 500000"
        const val NEW_API_USER_HEADER = "New-Api-User"

        fun applyPreset(preset: String, current: BalanceOption): BalanceOption =
            when (preset) {
                PRESET_NEW_API -> current.copy(
                    preset = PRESET_NEW_API,
                    apiPath = NEW_API_PATH,
                    resultPath = NEW_API_RESULT_PATH,
                )
                else -> current.copy(preset = PRESET_CUSTOM)
            }
    }
}
