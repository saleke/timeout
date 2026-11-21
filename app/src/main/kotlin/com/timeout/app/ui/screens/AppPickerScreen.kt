package com.timeout.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.ui.*
import kotlinx.coroutines.launch

@Composable
fun AppPickerScreen(state: AppUiState, draft: RuleDraft, onToggle: (String) -> Unit, onReload: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun search(value: String) {
        query = value
        scope.launch { scroll.scrollToItem(0) }
    }
    val filtered = remember(state.apps, query) { state.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    val missing = remember(draft.packages, state.apps) { draft.packages - state.apps.map { it.packageName }.toSet() }
    DetailScaffold("Choose apps", onDone, actions = {
        IconButton(onReload, enabled = !state.appsLoading) { Symbol(R.drawable.ic_refresh, description = "Refresh apps") }
    }, bottom = {
        BottomAction {
            Text("${appCount(draft.packages.size)} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MainButton("Use these apps", onDone, enabled = draft.packages.isNotEmpty(), icon = R.drawable.ic_check)
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = scroll,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "search", contentType = "search") {
                OutlinedTextField(query, ::search, Modifier.fillMaxWidth().padding(bottom = 12.dp), label = { Text("Search apps") },
                    leadingIcon = { Symbol(R.drawable.ic_search) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ search("") }) { Symbol(R.drawable.ic_close, description = "Clear search") } })
            }
            if (state.appsLoading) item(key = "loading", contentType = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            state.appsError?.let { error -> item(key = "error", contentType = "error") {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
            } }
            if (filtered.isEmpty() && !state.appsLoading) item(key = "empty", contentType = "empty") {
                Text(if (query.isEmpty()) "No selectable apps in this profile" else "No apps match your search",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(filtered, key = { it.packageName }, contentType = { "app" }) { app ->
                val selected = app.packageName in draft.packages
                val existing = state.policy.decisions[app.packageName]
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .toggleable(selected, enabled = !state.busy, role = Role.Checkbox, onValueChange = { onToggle(app.packageName) })
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppIcon(app)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        if (existing?.restricted == true) Text(existing.nextAccessAt?.let { "Existing limit · ${shortTimeLabel(context, it, state.clock.wallTime)}" }
                            ?: "Existing limit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(selected, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            if (!state.appsLoading) items(missing.toList(), key = { "missing:$it" }, contentType = { "missing" }) { name ->
                ListItem(headlineContent = { Text("Unavailable app") }, supportingContent = { Text(name) },
                    trailingContent = { TextButton({ onToggle(name) }, enabled = !state.busy) { Text("Remove") } })
            }
        }
    }
}
