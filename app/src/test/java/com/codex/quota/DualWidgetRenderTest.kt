package com.codex.quota

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import com.codex.quota.domain.model.*
import com.codex.quota.widget.DualQuotaWidgetContent
import com.codex.quota.widget.quotaRingBitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalGlanceApi::class)
class DualWidgetRenderTest {
    private val now = 2_000_000_000_000L
    private val previewDirectory = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "gradlew.bat").isFile }.resolve("../outputs/widget-previews").canonicalFile

    @Test fun compactAndDialLayoutsRenderBothAccountsAtLauncherSizes() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val fixtures = listOf(account("Personal", 75.0, 42.0), account("Work", 61.0, 88.0))
        for ((width, height) in listOf(320 to 64, 360 to 72, 280 to 56, 240 to 56, 280 to 48, 320 to 144, 360 to 160, 280 to 110)) {
            val widget = PreviewWidget(fixtures)
            val remoteViews = withTimeout(30_000) {
                widget.compose(context, size = DpSize(width.dp, height.dp), state = emptyPreferences())
            }
            val view = remoteViews.apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val pixelWidth = (width * density).toInt()
            val pixelHeight = (height * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, pixelWidth, pixelHeight)
            val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File(previewDirectory, "widget-${width}x$height.png")
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val texts = descendants(view).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
            assertTrue("Missing first account at ${width}x$height", texts.any { it.text.toString() == "Personal" })
            assertTrue("Missing second account at ${width}x$height", texts.any { it.text.toString() == "Work" })
            assertEquals(if (height < 100) 0 else 2, texts.count { it.text.toString() == "Weekly" })
            for (text in texts) {
                val layout = requireNotNull(text.layout)
                for (line in 0 until layout.lineCount) {
                    assertEquals("Clipped '${text.text}' at ${width}x$height", 0, layout.getEllipsisCount(line))
                }
                assertTrue("Zero height '${text.text}' at ${width}x$height", text.height > 0)
                assertTrue("Vertical clipping '${text.text}' at ${width}x$height",
                    text.height >= layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                val bounds = Rect()
                text.getDrawingRect(bounds)
                (view as ViewGroup).offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Outside widget '${text.text}' at ${width}x$height: $bounds",
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right <= pixelWidth && bounds.bottom <= pixelHeight)
            }
        }
    }

    @Test fun ringsDistinguishUnknownEmptyPartialAndFullQuota() {
        val accent = android.graphics.Color.GREEN
        val track = android.graphics.Color.DKGRAY
        fun coloredPixels(value: Double?): Int {
            val ring = quotaRingBitmap(value, accent, track)
            val pixels = IntArray(ring.width * ring.height)
            ring.getPixels(pixels, 0, ring.width, 0, 0, ring.width, ring.height)
            assertEquals(android.graphics.Color.TRANSPARENT, ring.getPixel(96, 96))
            return pixels.count { it == accent }
        }
        assertEquals(0, coloredPixels(null))
        assertEquals(0, coloredPixels(0.0))
        assertTrue(coloredPixels(25.0) < coloredPixels(75.0))
        assertTrue(coloredPixels(75.0) < coloredPixels(100.0))
    }

    private inner class PreviewWidget(private val accounts: List<AccountWithUsage>) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { GlanceTheme { DualQuotaWidgetContent(context, accounts, emptyPreferences(), WidgetThemeMode.DARK_OBSIDIAN, now) } }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun account(name: String, weekly: Double, hourly: Double) = AccountWithUsage(
        CodexAccount(name, name, null, PlanType.PLUS, null, "#10B981", AuthStatus.AUTHENTICATED, false, 0, 0L, now),
        CodexUsage.empty(name, AuthStatus.AUTHENTICATED).copy(remainingPercent = weekly,
            fiveHourRemainingPercent = hourly, resetAtEpochMs = now + 28 * 3_600_000L,
            fiveHourResetAtEpochMs = now + 75 * 60_000L, fetchedAtEpochMs = now))
}
