package com.momtime.shared.domain

/**
 * Opt-in, per template, default None (ADR 0020). Barcode scan or photo match only — never
 * physical exertion, math, or typing missions.
 */
sealed interface MissionConfig {
    data object None : MissionConfig

    data class Barcode(
        val expectedPayload: String,
    ) : MissionConfig

    data class PhotoMatch(
        val referenceHash: String,
    ) : MissionConfig
}
