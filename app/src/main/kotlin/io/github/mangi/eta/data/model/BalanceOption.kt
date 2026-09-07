package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val apiPath: String = "",
    val resultPath: String = "",
)
