package com.timeout.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.ui.*
import kotlinx.coroutines.launch

@Composable
fun AppPickerScreen(state: AppUiState, draft: RuleDraft, onToggle: (String) -> Unit, onReload: () -> Unit, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun search(value: String) {
        query = value
        scope.launch { scroll.scrollToItem(0) }
    }
    val filtered = remember(state.apps, query) { state.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    DetailScaffold("Choose apps", onDone, bottom = {
        BottomAction {
            Text("${appCount(draft.packages.size)} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MainButton("Use these apps", onDone, enabled = draft.packages.isNotEmpty(), icon = R.drawable.ic_check)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("What can wait?", style = MaterialTheme.typography.headlineLarge)
                Text("Choose distractions, and leave the apps you need available. Calls and essential system controls are kept out of this list.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, ::search, Modifier.fillMaxWidth(), placeholder = { Text("Search your apps") },
                    leadingIcon = { Symbol(R.drawable.ic_search) }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ search("") }) { Symbol(R.drawable.ic_close, description = "Clear search") } })
                if (state.appsError != null) Text(state.appsError, color = MaterialTheme.colorScheme.error)
                TextButton(onReload, enabled = !state.appsLoading) { Symbol(R.drawable.ic_refresh, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Refresh app list") }
            }
            if (state.appsLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                if (filtered.isEmpty()) item(key = "empty", contentType = "empty") {
                    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (query.isEmpty()) "No selectable apps in this profile" else "No apps match your search", style = MaterialTheme.typography.titleLarge)
                        Text("This list includes launchable apps in the current Android profile. Separate work profiles and websites need their own coverage.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(filtered, key = { it.packageName }, contentType = { "app" }) { app ->
                    val selected = app.packageName in draft.packages
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle(app.packageName) }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppIcon(app)
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Checkbox(selected, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
                val missing = draft.packages - state.apps.map { it.packageName }.toSet()
                items(missing.toList(), key = { "missing:$it" }, contentType = { "missing" }) { name ->
                    ListItem(headlineContent = { Text("Unavailable app") }, supportingContent = { Text(name) },
                        trailingContent = { TextButton({ onToggle(name) }) { Text("Remove") } })
                }
            }
        }
    }
}
