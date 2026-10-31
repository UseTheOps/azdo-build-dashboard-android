package com.azdo.dashboard

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val model: DashboardViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                DashboardApp(state, model)
            }
        }
    }
}

@Composable
private fun DashboardApp(state: UiState, model: DashboardViewModel) {
    BackHandler(enabled = state.organization.isNotBlank() && state.screen != Screen.Dashboard) { model.back() }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, bottomBar = {
        if (state.organization.isNotBlank()) NavigationBar {
            NavigationBarItem(selected = state.screen == Screen.Dashboard, onClick = model::dashboard,
                enabled = !state.switchingAccount,
                icon = { Text("▦") }, label = { Text("Dashboard") })
            NavigationBarItem(selected = state.screen == Screen.Projects || state.screen == Screen.Definitions,
                enabled = !state.switchingAccount,
                onClick = model::projects, icon = { Text("☷") }, label = { Text("Projects") })
            NavigationBarItem(selected = state.screen == Screen.Settings, onClick = { model.settings() },
                enabled = !state.switchingAccount,
                icon = { Text("⚙") }, label = { Text("Settings") })
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val wide = maxWidth > 840.dp
            Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (state.screen) {
                        Screen.Dashboard -> "Build dashboard"
                        Screen.Projects -> "Projects"
                        Screen.Definitions -> state.project?.name ?: "Build definitions"
                        Screen.Runs -> state.definition?.name ?: "Build runs"
                        Screen.Details -> "Build ${state.run?.number.orEmpty()}"
                        Screen.Reports -> "Report preview"
                        Screen.Settings -> "Settings"
                    }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (state.organization.isNotBlank() && state.screen != Screen.Dashboard)
                        TextButton(onClick = model::back, enabled = !state.switchingAccount) { Text("Back") }
                    if (state.organization.isNotBlank() && state.screen != Screen.Settings && state.screen != Screen.Reports)
                        TextButton(onClick = { model.refresh() }, enabled = !state.busy) { Text("Refresh") }
                }
                if (!state.initialized || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                state.cacheTime?.let {
                    Text("Saved data: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                        style = MaterialTheme.typography.labelSmall)
                }
                if (state.initialized) when (state.screen) {
                    Screen.Dashboard -> Dashboard(state, model, wide)
                    Screen.Projects -> ProjectList(state, model)
                    Screen.Definitions -> Definitions(state, model)
                    Screen.Runs -> Runs(state, model)
                    Screen.Details -> Details(state, model)
                    Screen.Reports -> Reports(state, model)
                    Screen.Settings -> Settings(state, model)
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) { Text(text, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge) }

@Composable
private fun Dashboard(state: UiState, model: DashboardViewModel, wide: Boolean) {
    var assigning by remember { mutableStateOf<Selection?>(null) }
    val groups = listOf(Category(-1, "Ungrouped")) + state.categories
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (state.selections.isEmpty()) item { Empty("No builds selected. Open Projects and choose build definitions.") }
        for (group in groups) {
            val rows = state.selections.filter { if (group.id == -1L) it.categoryId == null else it.categoryId == group.id }
            if (rows.isNotEmpty()) {
                item { Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                // Two columns on larger windows without depending on a navigation framework.
                items(rows.chunked(if (wide) 2 else 1)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { selection ->
                            Card(Modifier.weight(1f)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(selection.name, style = MaterialTheme.typography.titleMedium)
                                    Text(selection.projectName, style = MaterialTheme.typography.labelMedium)
                                    val latest = state.latest[model.selectionKey(selection)]
                                    if (latest == null) Text(if (state.latest.containsKey(model.selectionKey(selection))) "No runs found." else "Refresh to load latest build.")
                                    else {
                                        Text("#${latest.number} · ${latest.summary}")
                                        Text(latest.time, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row {
                                        TextButton(onClick = { model.showRuns(selection) }) { Text("Runs") }
                                        TextButton(onClick = { assigning = selection }) { Text("Category") }
                                    }
                                }
                            }
                        }
                        if (wide && pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    assigning?.let { selection ->
        AlertDialog(onDismissRequest = { assigning = null }, title = { Text("Assign category") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { model.assign(selection, null); assigning = null }) { Text("Ungrouped") }
                    state.categories.forEach { category ->
                        TextButton(onClick = { model.assign(selection, category.id); assigning = null }) { Text(category.name) }
                    }
                    if (state.categories.isEmpty()) Text("Create categories in Settings → Dashboard & cache.")
                }
            }, confirmButton = { TextButton(onClick = { assigning = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ProjectList(state: UiState, model: DashboardViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.projects.isEmpty() && !state.busy) item { Empty("No projects loaded. Refresh to browse accessible projects.") }
        items(state.projects, key = { it.id }) { project ->
            OutlinedCard(onClick = { model.selectProject(project) }, modifier = Modifier.fillMaxWidth()) {
                Text(project.name, Modifier.padding(20.dp))
            }
        }
        item { More(state, model) }
    }
}

@Composable
private fun Definitions(state: UiState, model: DashboardViewModel) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${state.draft.size} selected", Modifier.padding(vertical = 16.dp))
            Button(onClick = model::saveDefinitions, enabled = !state.busy) { Text("Save selection") }
        }
        LazyColumn {
            if (state.definitions.isEmpty() && !state.busy) item { Empty("No build definitions loaded.") }
            items(state.definitions, key = { it.id }) { definition ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Checkbox(checked = definition.id in state.draft, onCheckedChange = { model.toggleDefinition(definition.id) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(definition.name, style = MaterialTheme.typography.titleMedium)
                        Text(definition.path, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { More(state, model) }
        }
    }
}

@Composable
private fun More(state: UiState, model: DashboardViewModel) {
    if (state.next != null) TextButton(onClick = { model.refresh(true) }, enabled = !state.busy) { Text("Load more") }
}

@Composable
private fun Runs(state: UiState, model: DashboardViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.runs.isEmpty() && !state.busy) item { Empty("No runs found for this build definition.") }
        items(state.runs, key = { it.id }) { run ->
            OutlinedCard(onClick = { model.showRun(run) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("#${run.number}", style = MaterialTheme.typography.titleMedium)
                    Text(run.summary)
                    Text(run.time, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { More(state, model) }
    }
}

@Composable
private fun Details(state: UiState, model: DashboardViewModel) {
    var tab by remember(state.run?.id) { mutableIntStateOf(0) }
    state.run?.let { Text(it.summary, Modifier.padding(vertical = 8.dp)) }
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Tests (${state.tests.size})") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Artifacts (${state.artifacts.size})") })
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == 0) {
            if (state.tests.isEmpty() && !state.busy) item { Empty("No test results available. Some builds do not publish tests.") }
            items(state.tests) { test ->
                ListItem(headlineContent = { Text(test.name) },
                    supportingContent = { Text("${test.outcome} · ${test.duration} ms") })
            }
        } else {
            item { Text("Downloads happen only when you tap Preview. Supported: HTML, Markdown and bounded ZIP report archives.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) }
            if (state.artifacts.isEmpty() && !state.busy) item { Empty("No artifacts published by this build.") }
            items(state.artifacts) { artifact ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(artifact.name, style = MaterialTheme.typography.titleMedium)
                        Text("Type: ${artifact.type}")
                        TextButton(onClick = { model.openArtifact(artifact) }, enabled = artifact.url.isNotBlank() ||
                            artifact.type.equals("Container", true) || artifact.type.equals("PipelineArtifact", true)) { Text("Preview reports") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(state: UiState, model: DashboardViewModel) {
    TabRow(selectedTabIndex = state.settingsPage) {
        Tab(selected = state.settingsPage == 0, onClick = { model.settingsPage(0) }, text = { Text("Connection / account") })
        Tab(selected = state.settingsPage == 1, onClick = { model.settingsPage(1) }, text = { Text("Dashboard & cache") })
    }
    if (state.settingsPage == 0) Connection(state, model) else Configuration(state, model)
}

@Composable
private fun Connection(state: UiState, model: DashboardViewModel) {
    var organization by remember(state.organization) { mutableStateOf(state.organization) }
    // Deliberately not rememberSaveable: the PAT must never enter saved instance state.
    var pat by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to https://dev.azure.com/", Modifier.padding(top = 16.dp))
        Text("Use a PAT with Project and Team: Read, Build: Read, and Test Management: Read. Your token is encrypted locally and never displayed.")
        OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text("Organization name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
        OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("Personal access token") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, enabled = !state.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.connect(organization, pat, false) }, enabled = !state.busy && pat.isNotBlank()) { Text("Test") }
            Button(onClick = { model.connect(organization, pat, true); pat = "" }, enabled = !state.busy && pat.isNotBlank()) { Text("Save account") }
        }
        if (state.organization.isNotBlank()) {
            Text("Connected organization: ${state.organization}")
            Text("Saving an account clears all prior selections, categories and cached data, including when replacing a PAT in the same organization.")
            OutlinedButton(onClick = model::disconnect, enabled = !state.busy) { Text("Disconnect & erase local account") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Configuration(state: UiState, model: DashboardViewModel) {
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Category?>(null) }
    var editedName by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Category?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("New category") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { model.addCategory(name); name = "" }, enabled = name.isNotBlank()) { Text("Create category") }
        }
        items(state.categories, key = { it.id }) { category ->
            ListItem(headlineContent = { Text(category.name) }, supportingContent = {
                Row {
                    TextButton(onClick = { editing = category; editedName = category.name }) { Text("Rename") }
                    TextButton(onClick = { delete = category }) { Text("Delete") }
                }
            })
        }
        item {
            HorizontalDivider()
            Text("Local cache", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Projects, definitions, latest builds, runs, tests and artifact metadata are stored in Room for offline browsing. Report bodies are not persisted.")
            OutlinedButton(onClick = model::clearCache) { Text("Clear cache (keep selections)") }
            Text("Assign builds to categories from the dashboard. Deleting a category moves its builds to Ungrouped.")
            Spacer(Modifier.height(24.dp))
        }
    }
    editing?.let { category ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Rename category") },
            text = { OutlinedTextField(editedName, { editedName = it.take(80) }, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { model.renameCategory(category, editedName); editing = null }, enabled = editedName.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    delete?.let { category ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${category.name}?") },
            text = { Text("Build selections are kept and moved to Ungrouped.") },
            confirmButton = { TextButton(onClick = { model.deleteCategory(category); delete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Reports(state: UiState, model: DashboardViewModel) {
    val report = state.reports.getOrNull(state.reportIndex)
    if (report == null) {
        if (!state.busy) Empty("No report preview available. Go back to choose another artifact.")
        return
    }
    var choose by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { choose = true }) { Text("${report.name} ▾") }
        DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
            state.reports.forEachIndexed { index, item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = { model.chooseReport(index); choose = false })
            }
        }
    }
    Text("Read-only preview: scripts, links, images and external resources are disabled.", style = MaterialTheme.typography.labelSmall)
    key(report.name, report.text) {
        if (report.html) SafeHtml(report) else Markdown(report)
    }
}

@Composable
private fun Markdown(report: Report) {
    val html by produceState<String?>(null, report.text) {
        value = withContext(Dispatchers.Default) {
            val document = Parser.builder().build().parse(report.text)
            HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(document)
        }
    }
    html?.let { SafeHtml(report.copy(text = it, html = true)) }
        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun SafeHtml(report: Report) {
    key(report.name, report.text) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                val csp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"
                loadDataWithBaseURL("https://preview.invalid/", "<!doctype html><html><head>$csp</head><body>${report.text}</body></html>",
                    "text/html", "UTF-8", null)
            }
        }, onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() })
    }
}
