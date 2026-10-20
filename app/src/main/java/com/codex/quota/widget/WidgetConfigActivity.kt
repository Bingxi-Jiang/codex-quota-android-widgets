package com.codex.quota.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.codex.quota.worker.WorkScheduler
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.ui.theme.CodexQuotaTheme
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = application as CodexQuotaApplication
        val repository = app.repository
        val isDualWidget = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
            ?.provider?.className == MultiAccountQuotaWidgetReceiver::class.java.name
        var widgetPreferences by mutableStateOf<Preferences?>(null)
        if (isDualWidget) {
            lifecycleScope.launch {
                val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
                widgetPreferences = getAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, glanceId)
            }
        }

        setContent {
            CodexQuotaTheme {
                val accounts by repository.observeAccounts().collectAsState(initial = emptyList())
                if (isDualWidget) {
                    val loaded = widgetPreferences
                    if (loaded == null) {
                        Text("Loading widget settings…", modifier = Modifier.padding(24.dp))
                    } else {
                        DualWidgetConfiguration(accounts, loaded, ::confirmDualWidgetConfig)
                    }
                    return@CodexQuotaTheme
                }

                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Configure Widget") })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Choose an account to display on this widget:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        LazyColumn {
                            items(accounts, key = { it.account.id }) { item ->
                                AccountConfigItem(item = item) {
                                    confirmWidgetConfig(item.account.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountConfigItem(item: AccountWithUsage, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onClick() },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.account.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.account.planType.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = item.usage?.remainingPercent?.let { "${it.toInt()}%" } ?: "--%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    private fun confirmWidgetConfig(accountId: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WIDGET_ACCOUNT_ID] = accountId
            }
            finishWidgetConfiguration()
        }
    }

    private fun confirmDualWidgetConfig(firstId: String, secondId: String?, firstName: String, secondName: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WIDGET_ACCOUNT_ID] = firstId
                if (secondId == null) prefs.remove(WIDGET_SECOND_ACCOUNT_ID) else prefs[WIDGET_SECOND_ACCOUNT_ID] = secondId
                if (firstName.isBlank()) prefs.remove(WIDGET_FIRST_LABEL) else prefs[WIDGET_FIRST_LABEL] = firstName
                if (secondName.isBlank()) prefs.remove(WIDGET_SECOND_LABEL) else prefs[WIDGET_SECOND_LABEL] = secondName
            }
            finishWidgetConfiguration()
        }
    }

    private suspend fun finishWidgetConfiguration() {
        WidgetUpdateHelper.updateAllWidgets(applicationContext)
        WorkScheduler.requestImmediateRefresh(applicationContext)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
