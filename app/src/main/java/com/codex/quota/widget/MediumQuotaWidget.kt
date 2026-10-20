package com.codex.quota.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.Preferences
import androidx.glance.currentState
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.codex.quota.domain.model.WidgetThemeMode
import com.codex.quota.ui.MainActivity
import com.codex.quota.ui.util.formatQuotaPercent
import com.codex.quota.ui.util.formatResetCountdown
import com.codex.quota.ui.util.primarySubscriberQuotaWindow
import androidx.glance.appwidget.LinearProgressIndicator

class MediumQuotaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? CodexQuotaApplication
        val accounts = app?.repository?.getAllAccounts().orEmpty()
        val accountsFlow = app?.repository?.observeAccounts() ?: flowOf(emptyList())
        val preferencesFlow = app?.preferencesRepository?.observePreferences()
            ?: flowOf(com.codex.quota.domain.model.UserPreferences())
        val prefs = try {
            app?.preferencesRepository?.getPreferences()
        } catch (e: Exception) {
            null
        }

        provideContent {
            val liveAccounts by accountsFlow.collectAsState(initial = accounts)
            val livePreferences by preferencesFlow.collectAsState(
                initial = prefs ?: com.codex.quota.domain.model.UserPreferences()
            )
            GlanceTheme {
                MediumWidgetContent(context, selectWidgetAccount(liveAccounts, currentState<Preferences>()[WIDGET_ACCOUNT_ID]), livePreferences.widgetThemeMode)
            }
        }
    }

    @Composable
    private fun MediumWidgetContent(
        context: Context,
        data: AccountWithUsage?,
        themeMode: WidgetThemeMode
    ) {
        val size = LocalSize.current
        val height = size.height
        val colors = WidgetThemeHelper.getColors(themeMode)

        val intent = if (data != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("codexquota://account/${data.account.id}"), context, MainActivity::class.java)
        } else {
            Intent(context, MainActivity::class.java)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(colors.background)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(intent))
        ) {
            if (data == null) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Codex Quotas",
                        style = TextStyle(color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = "Tap to add an account",
                        style = TextStyle(color = colors.accentBlue, fontSize = 12.sp)
                    )
                }
            } else {
                val primaryWindow = data.usage.primarySubscriberQuotaWindow(data.account.planType)
                val remainingPercent = primaryWindow?.remainingPercent?.roundToInt() ?: data.usage?.remainingPercent?.roundToInt()
                val usedPercent = primaryWindow?.usedPercent?.roundToInt()
                    ?: data.usage?.usedPercent?.roundToInt()
                    ?: remainingPercent?.let { (100 - it).coerceIn(0, 100) }
                val status = data.usage?.status ?: data.account.authStatus
                val resetStr = primaryWindow?.resetAtEpochMs?.let { formatResetCountdown(it) }
                    ?: data.usage?.rateLimitInfo?.resetRequestsDuration
                    ?: data.usage?.rateLimitInfo?.resetTokensDuration
                    ?: "Active"
                val primaryLabel = primaryWindow?.label ?: "Quota"
                val secondaryQuotaText = if (data.usage?.fiveHourRemainingPercent != null && primaryWindow?.label == "Weekly") {
                    "5h ${formatQuotaPercent(data.usage.fiveHourRemainingPercent)}"
                } else {
                    null
                }

                val dotColor = try {
                    Color(android.graphics.Color.parseColor(data.account.colorHex))
                } catch (e: Exception) {
                    Color(0xFF10B981)
                }

                if (height < 85.dp) {
                    // Ultra-compact 1-row / 4x1 mode
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .width(8.dp)
                                .height(8.dp)
                                .background(dotColor)
                                .cornerRadius(4.dp)
                        ) {}

                        Spacer(modifier = GlanceModifier.width(8.dp))

                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = data.account.nickname,
                                maxLines = 1,
                                style = TextStyle(color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            )
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            // Progress bar
                            Text(
                                text = data.widgetSyncLabel(),
                                maxLines = 1,
                                style = TextStyle(color = colors.textMuted, fontSize = 9.sp)
                            )
                            LinearProgressIndicator(
                                progress = ((primaryWindow?.remainingPercent ?: data.usage?.remainingPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = colors.accent,
                                backgroundColor = colors.progressTrack
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(10.dp))

                        WidgetRefreshButton(colors, compact = true)
                        Text(
                            text = if (remainingPercent != null) "$remainingPercent%" else "--%",
                            style = TextStyle(color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        )
                    }
                } else {
                    // Standard / Large Card Mode (4x2 / 4x3 / 5x2)
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Header Row
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .background(dotColor)
                                    .cornerRadius(4.dp)
                            ) {}

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    text = data.account.nickname,
                                    maxLines = 1,
                                    style = TextStyle(color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                )
                                Text(
                                    text = secondaryQuotaText ?: data.account.planType.displayName,
                                    maxLines = 1,
                                    style = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            WidgetRefreshButton(colors)
                            if (status == AuthStatus.AUTHENTICATION_REQUIRED && remainingPercent == null) {
                                Text(
                                    text = "Sign In",
                                    style = TextStyle(color = colors.error, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                )
                            } else {
                                Text(
                                    text = if (remainingPercent != null) "$remainingPercent%" else "--%",
                                    style = TextStyle(color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height(8.dp))

                        if (height <= 150.dp) {
                            Text(
                                text = data.widgetSyncLabel(),
                                maxLines = 1,
                                style = TextStyle(color = colors.textMuted, fontSize = 9.sp)
                            )
                        }

                        // Progress Bar
                        LinearProgressIndicator(
                            progress = ((primaryWindow?.remainingPercent ?: data.usage?.remainingPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                            color = colors.accent,
                            backgroundColor = colors.progressTrack
                        )

                        Spacer(modifier = GlanceModifier.height(8.dp))

                        // Stats Pill Box
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .background(colors.surface)
                                .cornerRadius(10.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    text = if (primaryWindow != null) "${primaryLabel.uppercase()} USED" else "USED",
                                    style = TextStyle(color = colors.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (usedPercent != null) "$usedPercent%" else "--%",
                                    style = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                )
                            }

                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    text = if (primaryWindow != null) "${primaryLabel.uppercase()} RESET" else "RESET IN",
                                    style = TextStyle(color = colors.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = resetStr,
                                    maxLines = 1,
                                    style = TextStyle(color = colors.accentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        if (height > 150.dp) {
                            Spacer(modifier = GlanceModifier.height(8.dp))
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Codex Quotas",
                                    style = TextStyle(color = colors.textMuted, fontSize = 10.sp)
                                )
                                Spacer(modifier = GlanceModifier.defaultWeight())
                                Text(
                                    text = data.widgetSyncLabel(),
                                    style = TextStyle(
                                        color = if (data.usage?.isStale == true) ColorProvider(Color(0xFFF59E0B)) else colors.textMuted,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class MediumQuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MediumQuotaWidget()
}
