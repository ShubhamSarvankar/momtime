package com.momtime.shared.di

import org.koin.dsl.module
import kotlin.time.Clock

/**
 * CLAUDE.md invariant 8: no Clock.System call outside this module. Every time-dependent piece
 * of code takes an injected Clock instead — mechanically enforced by verifyNoClockSystem in
 * shared/build.gradle.kts, which exempts only this package.
 */
val clockModule =
    module {
        single<Clock> { Clock.System }
    }
