package com.momtime.android.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * What the app's drawable resources may hold (ADR 0072, PR 9). A bitmap in a drawable folder with no density qualifier
 * is treated by Android as mdpi and scaled by the screen's density: about three times on an xxhdpi screen, so a 1080 by
 * 2340 screenshot becomes a bitmap of roughly 90 MB and the app can run out of memory. Screenshots therefore live in
 * `drawable-nodpi`, which Android does not scale, and no screenshot may exceed 1080 pixels wide or 400 KB.
 *
 * The audit is a function of a resource directory, so each rule is proved on fixtures, and the real tree is then audited
 * with it. The four Samsung step drawables are placeholders (vector shapes, not bitmaps) until real screenshots replace
 * them, so on the real tree today the audit finds no bitmap at all; the fixtures are what show it can find one.
 */
class DrawableDensityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val res: File get() = folder.root

    private fun png(
        dir: String,
        name: String,
        width: Int = 10,
        paddedTo: Int = 0,
    ): File {
        val file = File(File(res, dir).also { it.mkdirs() }, name)
        ImageIO.write(BufferedImage(width, 1, BufferedImage.TYPE_INT_RGB), "png", file)
        // Bytes after the end of a PNG are ignored by readers, so this sets the file's size exactly.
        if (file.length() < paddedTo) file.appendBytes(ByteArray((paddedTo - file.length()).toInt()))
        return file
    }

    @Test
    fun `a bitmap in a drawable folder with no density qualifier is flagged`() {
        png("drawable", "a.png")
        png("drawable-night", "b.png")
        png("drawable-v24", "c.png")
        png("drawable-en", "d.png")

        val found = DrawableAudit.withoutDensity(res)

        assertEquals(4, found.size)
        assertTrue(found.toString(), found.any { it.startsWith("drawable/a.png") })
    }

    @Test
    fun `a bitmap in a folder with a density qualifier is not flagged`() {
        for (dir in listOf(
            "drawable-nodpi",
            "drawable-mdpi",
            "drawable-hdpi",
            "drawable-xhdpi",
            "drawable-xxhdpi",
            "drawable-xxxhdpi",
            "drawable-night-xxhdpi",
            "drawable-anydpi",
            "drawable-ldpi",
            "drawable-tvdpi",
        )) {
            png(dir, "x.png")
        }

        assertEquals(emptyList<String>(), DrawableAudit.withoutDensity(res))
    }

    @Test
    fun `a drawable that is not a bitmap is not flagged wherever it is`() {
        File(File(res, "drawable").also { it.mkdirs() }, "shape.xml").writeText("<shape />")
        File(File(res, "drawable-v24").also { it.mkdirs() }, "vector.xml").writeText("<vector />")

        assertEquals(emptyList<String>(), DrawableAudit.withoutDensity(res))
        assertEquals(emptyList<String>(), DrawableAudit.oversized(res))
    }

    @Test
    fun `a screenshot wider than 1080 pixels is flagged, and one exactly 1080 wide is not`() {
        png("drawable-nodpi", "wide.png", width = 1081)
        png("drawable-nodpi", "exact.png", width = 1080)

        val found = DrawableAudit.oversized(res)

        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single().startsWith("drawable-nodpi/wide.png"))
    }

    @Test
    fun `a screenshot larger than 400 KB is flagged, and one exactly 400 KB is not`() {
        png("drawable-nodpi", "big.png", paddedTo = DrawableAudit.MAX_BYTES + 1)
        png("drawable-nodpi", "exact.png", paddedTo = DrawableAudit.MAX_BYTES)

        val found = DrawableAudit.oversized(res)

        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single().startsWith("drawable-nodpi/big.png"))
    }

    @Test
    fun `a bitmap that cannot be read is flagged, so an unsupported format cannot hide an oversized one`() {
        File(File(res, "drawable-nodpi").also { it.mkdirs() }, "shot.webp").writeBytes(ByteArray(16))

        val found = DrawableAudit.oversized(res)

        assertEquals(1, found.size)
        assertTrue(found.single().contains("cannot be read"))
    }

    @Test
    fun `the limits are 1080 pixels and 400 KB`() {
        assertEquals(1080, DrawableAudit.MAX_WIDTH)
        assertEquals(400 * 1024, DrawableAudit.MAX_BYTES)
    }

    // The real tree. The four placeholders are in drawable-nodpi, where a screenshot will replace them, and no bitmap is
    // anywhere a density would scale it or larger than a screenshot may be.
    @Test
    fun `the app's own drawables have no bitmap without a density qualifier and none too large`() {
        val real = File("src/main/res")
        assertTrue("run from the android module: ${real.absolutePath}", real.isDirectory)

        assertEquals(emptyList<String>(), DrawableAudit.withoutDensity(real))
        assertEquals(emptyList<String>(), DrawableAudit.oversized(real))
    }

    @Test
    fun `the four screenshot placeholders are in drawable-nodpi and nowhere else`() {
        val real = File("src/main/res")
        val names =
            listOf(
                "samsung_step_battery",
                "samsung_step_sleeping_apps",
                "samsung_step_deep_sleeping_apps",
                "samsung_step_unused_apps",
            )

        for (name in names) {
            val inNoDpi = real.resolve("drawable-nodpi").listFiles { f -> f.nameWithoutExtension == name }.orEmpty()
            val elsewhere =
                real
                    .listFiles { f ->
                        f.isDirectory &&
                            f.name.startsWith("drawable") &&
                            f.name != "drawable-nodpi"
                    }.orEmpty()
                    .flatMap { dir -> dir.listFiles { f -> f.nameWithoutExtension == name }.orEmpty().toList() }
            assertEquals("$name is in drawable-nodpi", 1, inNoDpi.size)
            assertEquals("$name is nowhere else: $elsewhere", 0, elsewhere.size)
        }
    }
}

/** The audit of a resource directory's bitmaps. */
internal object DrawableAudit {
    const val MAX_WIDTH = 1080
    const val MAX_BYTES = 400 * 1024

    private val densities = setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi", "tvdpi")
    private val bitmapExtensions = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")

    /** Every bitmap in a drawable folder under [res], as the folder's name and the file's. */
    private fun bitmaps(res: File): List<Pair<File, File>> =
        res
            .listFiles { f -> f.isDirectory && (f.name == "drawable" || f.name.startsWith("drawable-")) }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { dir ->
                dir
                    .listFiles { f -> f.isFile && f.extension.lowercase() in bitmapExtensions }
                    .orEmpty()
                    .sortedBy { it.name }
                    .map { dir to it }
            }

    private fun hasDensity(dir: File) =
        dir.name
            .split('-')
            .drop(1)
            .any { it in densities }

    /** Bitmaps in a drawable folder whose name carries no density qualifier. */
    fun withoutDensity(res: File): List<String> =
        bitmaps(res)
            .filterNot { (dir, _) -> hasDensity(dir) }
            .map { (dir, file) ->
                "${dir.name}/${file.name}: a bitmap in a folder with no density qualifier is scaled by the screen's density"
            }

    /** Bitmaps wider than [MAX_WIDTH] pixels or larger than [MAX_BYTES], and bitmaps that cannot be measured. */
    fun oversized(res: File): List<String> =
        bitmaps(res).mapNotNull { (dir, file) ->
            val label = "${dir.name}/${file.name}"
            val image = runCatching { ImageIO.read(file) }.getOrNull()
            when {
                image == null -> "$label: cannot be read, so its size is unknown (use a PNG)"
                image.width > MAX_WIDTH -> "$label: ${image.width} pixels wide, more than $MAX_WIDTH"
                file.length() > MAX_BYTES -> "$label: ${file.length()} bytes, more than $MAX_BYTES"
                else -> null
            }
        }
}
