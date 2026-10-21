package com.momtime.shared.engine

import com.momtime.shared.data.JvmDatabaseDriverFactory
import com.momtime.shared.data.MomTimeDatabase
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.SqlDelightPregnancyRepository
import com.momtime.shared.domain.DueDateRevision
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The week of pregnancy from the due date alone (ADR 0086's neighbour, `ARCHITECTURE.md` 3.5), and from the due date
 * in force at the instant being reported for. It is a jvm test because the revision case reaches
 * `PregnancyRepository.dueDateRevisionAsOf`, the production reader a report calls; the pure cases need nothing else.
 */
class GestationalAgeTest {
    private val due = LocalDate(2026, 12, 1)
    private val driver = JvmDatabaseDriverFactory.inMemory().createDriver()
    private lateinit var pregnancies: PregnancyRepository

    @BeforeTest
    fun setUp() {
        pregnancies = SqlDelightPregnancyRepository(MomTimeDatabase(driver))
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun daysAhead(days: Int) = GestationalAge.weekAt(due, due.minus(days, DateTimeUnit.DAY))

    private fun daysPast(days: Int) = GestationalAge.weekAt(due, due.plus(days, DateTimeUnit.DAY))

    @Test
    fun `the plan's four dates give weeks 0, 40, 42 and none`() {
        assertEquals(0, daysAhead(280), "280 days ahead is the first day of week 0")
        assertEquals(40, daysAhead(0), "on the due date it is week 40")
        assertEquals(42, daysPast(14), "14 days past is week 42")
        assertEquals(null, daysAhead(300), "300 days ahead is before week 0")
    }

    // Truncating toward zero gives week 0 for 281 to 286 days ahead, where the answer is that there is no week.
    @Test
    fun `a date before week 0 has no week, and so does a date after week 42`() {
        assertEquals(null, daysAhead(283), "283 days ahead is before week 0, not week 0")
        assertEquals(null, daysAhead(281))
        assertEquals(null, daysAhead(286))
        assertEquals(null, daysAhead(287))
        assertEquals(0, daysAhead(279), "279 days ahead is the second day of week 0")
        assertEquals(1, daysAhead(273), "273 days ahead starts week 1")
        assertEquals(42, daysPast(20), "20 days past is still week 42")
        assertEquals(null, daysPast(21), "21 days past is after week 42")
    }

    // Reaches the reader a report calls. Reading the latest revision always would give the later due date's week
    // for a report as of before the revision.
    @Test
    fun `a report as of before a revision gives the earlier due date's week`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        pregnancies.insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        val first = Instant.parse("2026-06-01T00:00:00Z")
        val second = Instant.parse("2026-09-01T00:00:00Z")
        pregnancies.insertDueDateRevision(DueDateRevision("r1", "preg", LocalDate(2026, 12, 1), first))
        pregnancies.insertDueDateRevision(DueDateRevision("r2", "preg", LocalDate(2026, 12, 15), second))
        val onDate = LocalDate(2026, 10, 20)

        fun weekAsOf(asOf: Instant) =
            GestationalAge.weekAt(checkNotNull(pregnancies.dueDateRevisionAsOf("preg", asOf)).dueDate, onDate)

        assertEquals(34, weekAsOf(second - 1.seconds), "as of before the revision: due 1 December")
        assertEquals(32, weekAsOf(second), "as of the revision: due 15 December")
        assertEquals(32, weekAsOf(Instant.parse("2026-10-01T00:00:00Z")), "as of after the revision")
    }
}
