package com.codex.quota.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.codex.quota.ui.util.formatQuotaPercent

/** Only the arc is rasterized: percentage, labels and reset times remain accessible native text. */
@Composable
internal fun QuotaDial(
    label: String, remainingPercent: Double?, resetAt: Long?, diameter: Float,
    accent: ColorProvider, colors: WidgetColors, now: Long, modifier: GlanceModifier
) {
    val context = LocalContext.current
    val arcColor = accent.getColor(context).toArgb()
    val trackColor = colors.progressTrack.getColor(context).toArgb()
    val ring = remember(remainingPercent, arcColor, trackColor) {
        quotaRingBitmap(remainingPercent, arcColor, trackColor)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TextStyle(color = colors.textSecondary, fontSize = 9.sp), maxLines = 1)
        Spacer(GlanceModifier.height(2.dp))
        Box(GlanceModifier.size(diameter.dp), contentAlignment = Alignment.Center) {
            Image(provider = ImageProvider(ring), contentDescription = null, modifier = GlanceModifier.size(diameter.dp))
            Text(formatQuotaPercent(remainingPercent), maxLines = 1,
                style = TextStyle(color = colors.textPrimary, fontSize = if (diameter < 46f) 11.sp else 14.sp,
                    fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(2.dp))
        Text(compactResetLabel(resetAt, now), maxLines = 1,
            style = TextStyle(color = colors.textSecondary, fontSize = 9.sp))
    }
}

internal fun quotaRingBitmap(remainingPercent: Double?, arcColor: Int, trackColor: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = trackColor
    }
    val bounds = RectF(10f, 10f, 182f, 182f)
    canvas.drawArc(bounds, -90f, 360f, false, paint)
    val value = remainingPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    if (value != null && value > 0.0) {
        paint.color = arcColor
        canvas.drawArc(bounds, -90f, (value * 3.6).toFloat(), false, paint)
    }
    return bitmap
}
