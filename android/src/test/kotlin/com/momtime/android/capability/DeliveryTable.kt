package com.momtime.android.capability

/** One row of `delivery-resolution-table.csv`: the inputs, the expected resolution, and the line it came from. */
internal data class TableRow(
    val inputs: CapabilityInputs,
    val expected: DeliveryResolution,
    val line: String,
)

/**
 * The expected delivery resolution for every combination of the six inputs (ADR 0050), read from data
 * written out by hand and not computed by the code under test.
 */
internal object DeliveryTable {
    private fun String.flag() = this == "1"

    fun rows(): List<TableRow> {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("delivery-resolution-table.csv")) {
                "delivery-resolution-table.csv is not on the test classpath"
            }.bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        check(lines.first().startsWith("exact,")) { "the table header is missing" }
        return lines.drop(1).map { line ->
            val c = line.split(",")
            check(c.size == 13) { "a row must have 13 columns: $line" }
            TableRow(
                inputs = CapabilityInputs(c[0].flag(), c[1].flag(), c[2].flag(), c[3].flag(), c[4].flag(), c[5].flag()),
                expected =
                    DeliveryResolution(
                        tier = ResolvedTier.valueOf(c[6]),
                        mechanism = DeliveryMechanism.valueOf(c[7]),
                        fullScreenIntent = c[8].flag(),
                        headsUp = c[9].flag(),
                        overlayAvailable = c[10].flag(),
                        audioOnly = c[11].flag(),
                        undeliverable = c[12].flag(),
                    ),
                line = line,
            )
        }
    }
}
