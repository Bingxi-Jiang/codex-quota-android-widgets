package com.codex.quota.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.UserPreferences
import com.codex.quota.domain.model.WidgetThemeMode
import com.codex.quota.ui.MainActivity
import kotlinx.coroutines.flow.flowOf

class MultiAccountQuotaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? CodexQuotaApplication
        val accounts = app?.repository?.getAllAccounts().orEmpty()
        val accountsFlow = app?.repository?.observeAccounts() ?: flowOf(emptyList())
        val prefs = app?.preferencesRepository?.getPreferences() ?: UserPreferences()
        val prefsFlow = app?.preferencesRepository?.observePreferences() ?: flowOf(prefs)
        provideContent {
            val liveAccounts by accountsFlow.collectAsState(initial = accounts)
            val livePrefs by prefsFlow.collectAsState(initial = prefs)
            GlanceTheme {
                DualQuotaWidgetContent(context, liveAccounts, currentState<Preferences>(), livePrefs.widgetThemeMode)
            }
        }
    }
}

/** The same two accounts become horizontal rows at 4x1 and adjacent dial panels at 4x2. */
@Composable
internal fun DualQuotaWidgetContent(
    context: Context,
    accounts: List<AccountWithUsage>,
    preferences: Preferences,
    themeMode: WidgetThemeMode,
    now: Long = System.currentTimeMillis()
) {
    val colors = WidgetThemeHelper.getColors(themeMode)
    val slots = dualWidgetAccounts(accounts, preferences)
    val size = LocalSize.current
    val compact = size.height < 108.dp
    Box(
        modifier = GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(18.dp)
            .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 8.dp)
    ) {
        if (compact) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                for ((index, slot) in slots.withIndex()) {
                    CompactAccountRow(context, slot, colors, now, GlanceModifier.defaultWeight())
                    if (index == 0) {
                        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(colors.progressTrack)) {}
                    }
                }
            }
        } else {
            val reservedHeight = if (size.height < 128.dp) 78f else 90f
            val diameter = minOf(size.height.value - reservedHeight, (size.width.value - 52f) / 4f).coerceIn(32f, 60f)
            Row(modifier = GlanceModifier.fillMaxSize()) {
                DialAccountPanel(context, slots[0], colors, diameter, now, GlanceModifier.defaultWeight())
                Box(GlanceModifier.fillMaxHeight().width(1.dp).background(colors.progressTrack)) {}
                DialAccountPanel(context, slots[1], colors, diameter, now, GlanceModifier.defaultWeight())
            }
        }
    }
}

private fun accountIntent(context: Context, slot: DualWidgetAccount) =
    Intent(Intent.ACTION_VIEW, Uri.parse(slot.data?.let { "codexquota://account/${it.account.id}" }
        ?: "codexquota://dashboard"), context, MainActivity::class.java)

@Composable
private fun CompactAccountRow(
    context: Context, slot: DualWidgetAccount, colors: WidgetColors, now: Long, modifier: GlanceModifier
) {
    val usage = slot.data?.usage
    val syncState = compactSyncState(slot.data)
    val width = LocalSize.current.width.value
    val metricWidth = ((width - 104f) / 2f).coerceIn(66f, 110f).dp
    val metricFont = if (width < 300f) 9.sp else 10.sp
    Row(
        modifier = modifier.fillMaxWidth().clickable(actionStartActivity(accountIntent(context, slot))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountDot(slot, colors)
        Spacer(GlanceModifier.width(5.dp))
        Text(slot.label, modifier = GlanceModifier.defaultWeight(), maxLines = 1,
            style = TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.width(4.dp))
        Text(compactQuotaLabel("W", usage?.remainingPercent, usage?.resetAtEpochMs, now),
            modifier = GlanceModifier.width(metricWidth), maxLines = 1,
            style = TextStyle(color = colors.accent, fontSize = metricFont, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.width(4.dp))
        Text(compactQuotaLabel("5h", usage?.fiveHourRemainingPercent, usage?.fiveHourResetAtEpochMs, now),
            modifier = GlanceModifier.width(metricWidth), maxLines = 1,
            style = TextStyle(color = colors.accentBlue, fontSize = metricFont, fontWeight = FontWeight.Medium))
        if (syncState != null) {
            Text(syncState, style = TextStyle(color = colors.error, fontSize = 9.sp), maxLines = 1)
        }
        WidgetRefreshButton(colors, compact = true)
    }
}

@Composable
private fun DialAccountPanel(
    context: Context, slot: DualWidgetAccount, colors: WidgetColors,
    diameter: Float, now: Long, modifier: GlanceModifier
) {
    val usage = slot.data?.usage
    val gap = if (LocalSize.current.height < 144.dp) 2.dp else 4.dp
    val inlineSync = LocalSize.current.height < 128.dp
    val syncLabel = slot.data?.widgetSyncLabel(now) ?: "Tap to add account"
    Column(
        modifier = modifier.fillMaxHeight().padding(horizontal = 6.dp)
            .clickable(actionStartActivity(accountIntent(context, slot))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AccountDot(slot, colors)
            Spacer(GlanceModifier.width(5.dp))
            Text(slot.label, modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            if (inlineSync) {
                Text(compactSyncState(slot.data).orEmpty() + syncLabel.substringAfter(" · ", "—"),
                    maxLines = 1, style = TextStyle(color = colors.textSecondary, fontSize = 8.sp))
            }
            WidgetRefreshButton(colors, compact = true)
        }
        Spacer(GlanceModifier.height(gap))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QuotaDial("Weekly", usage?.remainingPercent, usage?.resetAtEpochMs, diameter, colors.accent,
                colors, now, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(4.dp))
            QuotaDial("5-hour", usage?.fiveHourRemainingPercent, usage?.fiveHourResetAtEpochMs, diameter,
                colors.accentBlue, colors, now, GlanceModifier.defaultWeight())
        }
        if (!inlineSync) {
            Spacer(GlanceModifier.height(gap))
            Text(syncLabel, maxLines = 1, style = TextStyle(color = colors.textSecondary, fontSize = 9.sp))
        }
    }
}

@Composable
private fun AccountDot(slot: DualWidgetAccount, colors: WidgetColors) {
    val color = slot.data?.account?.colorHex?.let {
        runCatching { ColorProvider(Color(android.graphics.Color.parseColor(it))) }.getOrNull()
    } ?: colors.textMuted
    Box(GlanceModifier.width(5.dp).height(5.dp).background(color).cornerRadius(3.dp)) {}
}

private fun compactSyncState(data: AccountWithUsage?): String? = when (data?.usage?.status ?: data?.account?.authStatus) {
    AuthStatus.AUTHENTICATION_REQUIRED -> "!"
    AuthStatus.OFFLINE, AuthStatus.TEMPORARY_ERROR -> "~"
    else -> if (data?.usage?.isStale == true) "~" else null
}

class MultiAccountQuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MultiAccountQuotaWidget()
}
