package com.codex.quota.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import com.codex.quota.domain.model.AccountWithUsage

@Composable
internal fun DualWidgetConfiguration(
    accounts: List<AccountWithUsage>,
    preferences: Preferences,
    onSave: (String, String?, String, String) -> Unit
) {
    val ids = accounts.map { it.account.id }
    val initial = remember(ids, preferences) { dualWidgetAccounts(accounts, preferences) }
    var first by remember(ids) { mutableStateOf(initial[0]) }
    var second by remember(ids) { mutableStateOf(initial[1]) }
    var firstName by remember(ids) { mutableStateOf(first.data?.let { first.label }.orEmpty()) }
    var secondName by remember(ids) { mutableStateOf(second.data?.let { second.label }.orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Two-account widget", style = MaterialTheme.typography.headlineSmall)
        Text("4×1: two compact rows\n4×2: two accounts side by side, with weekly and 5-hour dials.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Percentages show quota remaining. Times show when each quota window resets.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ((first.data == null || second.data == null) && accounts.isNotEmpty()) {
            TextButton(onClick = {
                first = DualWidgetAccount(accounts.first(), accounts.first().account.nickname)
                second = DualWidgetAccount(accounts.getOrNull(1), accounts.getOrNull(1)?.account?.nickname ?: "Add account")
                firstName = ""
                secondName = ""
            }, enabled = !saving) { Text("Use current accounts") }
        }
        Spacer(Modifier.height(4.dp))
        Text("First account · left / top", style = MaterialTheme.typography.titleSmall)
        Text(first.data?.account?.nickname ?: "Add an account in the app first.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = firstName, onValueChange = { firstName = it.take(40) },
            label = { Text("Widget display name") }, singleLine = true, enabled = !saving,
            modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            val oldFirst = first
            first = second
            second = oldFirst
            val oldName = firstName
            firstName = secondName
            secondName = oldName
        }, enabled = first.data != null && second.data != null && !saving) { Text("Swap accounts") }
        Text("Second account · right / bottom", style = MaterialTheme.typography.titleSmall)
        Text(second.data?.account?.nickname ?: "Add your second account in the app to fill this slot.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = secondName, onValueChange = { secondName = it.take(40) },
            label = { Text("Widget display name") }, singleLine = true, enabled = !saving,
            modifier = Modifier.fillMaxWidth())
        Text("Names apply to this widget only. Leave a name blank to use the account nickname.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            val firstId = first.data?.account?.id ?: return@Button
            saving = true
            onSave(firstId, second.data?.account?.id, firstName.trim(), secondName.trim())
        }, enabled = first.data != null && !saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "Saving…" else "Save widget")
        }
    }
}
