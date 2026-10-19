package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.engine.RungDelivery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The delivery decision over the real repositories (ADR 0060): Phase 1's quiet hours and budget resolution,
 * applied to her stored settings and today's count, with the budget spent through the domain.
 */
class DeliveryPolicyCommandTest {
    private lateinit var driver: SqlDriver
    private lateinit var settings: AppSettingsRepository
    private lateinit var budget: InterruptionBudgetRepository
    private lateinit var policy: DeliveryPolicyCommand
    private var zone: TimeZone = TimeZone.UTC

    // 14:00 on the 1st, outside any window the tests set unless they say otherwise.
    private val afternoon = Instant.parse("2026-03-01T14:00:00Z")
    private val night = Instant.parse("2026-03-01T23:00:00Z")

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        settings = SqlDelightAppSettingsRepository(database)
        settings.ensureSeeded()
        budget = SqlDelightInterruptionBudgetRepository(database)
        policy = DeliveryPolicyCommand(settings, budget) { zone }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `with no quiet hours and a budget left a rung rings`() {
        assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, afternoon))
    }

    // ADR 0089: a Gentle rung outside quiet hours and within the budget is a notification, through the command.
    @Test
    fun `a gentle rung is a notification`() {
        assertEquals(RungDelivery.NOTIFICATION, policy.decide(Criticality.GENTLE, afternoon))
    }

    @Test
    fun `quiet hours silence a standard rung and never a critical one`() {
        settings.updateQuietHours(QuietHours(LocalTime(22, 0), LocalTime(6, 0)))
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.STANDARD, night))
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.GENTLE, night))
        assertEquals(RungDelivery.RING, policy.decide(Criticality.CRITICAL, night))
        assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, afternoon), "outside the window it rings")
    }

    @Test
    fun `quiet hours follow the current zone`() {
        settings.updateQuietHours(QuietHours(LocalTime(22, 0), LocalTime(6, 0)))
        // 23:00 UTC is 04:30 the next morning in Kolkata, inside the window there too; 14:00 UTC is 19:30.
        zone = TimeZone.of("Asia/Kolkata")
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.STANDARD, night))
        assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, afternoon))
        // 17:00 UTC is 22:30 in Kolkata: inside the window there, outside it in UTC.
        assertEquals(
            RungDelivery.SILENT_NOTIFICATION,
            policy.decide(Criticality.STANDARD, Instant.parse("2026-03-01T17:00:00Z")),
        )
    }

    @Test
    fun `a rung rings until the budget is spent and then goes silent, a critical one never does`() {
        settings.updateRingGradeDailyBudget(2)
        repeat(2) {
            assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, afternoon))
            policy.recordRing(afternoon)
        }
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.STANDARD, afternoon))
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.GENTLE, afternoon))
        assertEquals(RungDelivery.RING, policy.decide(Criticality.CRITICAL, afternoon))
    }

    @Test
    fun `deciding spends nothing`() {
        settings.updateRingGradeDailyBudget(1)
        repeat(5) { assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, afternoon)) }
        assertEquals(0, budget.current().ringCount)
    }

    @Test
    fun `recording a ring counts it once`() {
        policy.recordRing(afternoon)
        assertEquals(1, budget.current().ringCount)
        assertEquals(LocalDate(2026, 3, 1), budget.current().budgetDate)
    }

    @Test
    fun `a new local day starts the budget again`() {
        settings.updateRingGradeDailyBudget(1)
        policy.recordRing(afternoon)
        assertEquals(RungDelivery.SILENT_NOTIFICATION, policy.decide(Criticality.STANDARD, afternoon))

        val tomorrow = afternoon + 24.hours
        assertEquals(RungDelivery.RING, policy.decide(Criticality.STANDARD, tomorrow))
        policy.recordRing(tomorrow)
        assertEquals(LocalDate(2026, 3, 2), budget.current().budgetDate)
        assertEquals(1, budget.current().ringCount, "the count started again at one, not two")
    }

    // The day is the local date in the current zone (ADR 0032): 20:00 UTC on the 1st is already the 2nd in Kolkata.
    @Test
    fun `the budget day is the local date in the current zone`() {
        zone = TimeZone.of("Asia/Kolkata")
        policy.recordRing(Instant.parse("2026-03-01T20:00:00Z"))
        assertEquals(LocalDate(2026, 3, 2), budget.current().budgetDate)
    }
}
