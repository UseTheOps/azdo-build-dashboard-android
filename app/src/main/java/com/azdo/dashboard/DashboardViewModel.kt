package com.azdo.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Screen { Dashboard, Projects, Definitions, Runs, Details, Reports, Settings }
data class UiState(
    val initialized: Boolean = false,
    val organization: String = "",
    val screen: Screen = Screen.Dashboard,
    val settingsPage: Int = 0,
    val busy: Boolean = false,
    val switchingAccount: Boolean = false,
    val message: String? = null,
    val projects: List<Project> = emptyList(),
    val definitions: List<Definition> = emptyList(),
    val runs: List<Run> = emptyList(),
    val tests: List<TestResult> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val reports: List<Report> = emptyList(),
    val reportIndex: Int = 0,
    val next: String? = null,
    val project: Project? = null,
    val definition: Selection? = null,
    val run: Run? = null,
    val selections: List<Selection> = emptyList(),
    val categories: List<Category> = emptyList(),
    val draft: Set<Int> = emptySet(),
    val latest: Map<String, Run?> = emptyMap(),
    val cacheTime: Long? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = DashboardDatabase.create(application).dao()
    private val store = AccountStore(application)
    private var account: Account? = null
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var accountGeneration = 0L
    private var accountTransition = false
    private val writes = Mutex()

    init {
        viewModelScope.launch {
            account = withContext(Dispatchers.IO) { store.read() }
            if (account == null) dao.clearAccount()
            val selections = dao.selections().first()
            val categories = dao.categories().first()
            mutable.update { it.copy(initialized = true, organization = account?.organization.orEmpty(),
                screen = if (account == null) Screen.Settings else Screen.Dashboard,
                selections = selections, categories = categories) }
            launch { dao.selections().collect { rows -> mutable.update { it.copy(selections = rows) } } }
            launch { dao.categories().collect { rows -> mutable.update { it.copy(categories = rows) } } }
            if (account != null) refresh()
        }
    }

    private fun key(selection: Selection) = "${selection.projectId}:${selection.definitionId}"
    fun selectionKey(selection: Selection) = key(selection)
    private fun safeError(error: Exception) = (error as? ApiFailure)?.safeMessage
        ?: "Unable to complete the request. Check connectivity and try again."
    private fun navigate(screen: Screen, change: (UiState) -> UiState = { it }): Boolean {
        if (accountTransition) return false
        request?.cancel()
        generation++
        mutable.update { change(it).copy(screen = screen, busy = false, message = null, next = null, cacheTime = null) }
        return true
    }
    private fun task(action: suspend (Long, AzureApi) -> Unit) {
        if (accountTransition) return
        request?.cancel()
        val id = ++generation
        val savedAccount = account ?: return
        mutable.update { it.copy(busy = true, message = null) }
        request = viewModelScope.launch {
            try { action(id, AzureApi(savedAccount)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (id == generation) mutable.update { it.copy(message = safeError(error)) } }
            finally { if (id == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun update(id: Long, action: (UiState) -> UiState) {
        if (id == generation) mutable.update(action)
    }
    private fun cachedPage(cache: Cache): JsonPage {
        val json = JSONObject(cache.json)
        return JsonPage(json.getJSONArray("value"), json.optString("next").takeIf { it.isNotBlank() })
    }
    private suspend fun load(
        id: Long, cacheKey: String,
        display: suspend (JsonPage) -> Unit, fetch: suspend () -> JsonPage
    ) {
        val cached = dao.cache(cacheKey)
        if (cached != null && id == generation) {
            display(withContext(Dispatchers.IO) { cachedPage(cached) })
            update(id) { it.copy(cacheTime = cached.updated) }
        }
        try {
            val page = fetch()
            writes.withLock {
                if (id != generation) return
                val json = withContext(Dispatchers.IO) {
                    JSONObject().put("value", page.values).put("next", page.next.orEmpty()).toString()
                }
                dao.putCache(Cache(cacheKey, json, System.currentTimeMillis()))
                display(page)
                update(id) { it.copy(cacheTime = System.currentTimeMillis()) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (cached == null) throw error
            update(id) { it.copy(message = "${safeError(error)} Showing saved data.") }
        }
    }
    private fun objects(page: JsonPage): List<JSONObject> = (0 until page.values.length()).map { page.values.getJSONObject(it) }

    fun dashboard() { if (navigate(Screen.Dashboard)) refresh() }
    fun projects() { if (navigate(Screen.Projects) { it.copy(projects = emptyList()) }) refresh() }
    fun settings(page: Int = 0) { navigate(Screen.Settings) { it.copy(settingsPage = page) } }
    fun settingsPage(page: Int) { if (!accountTransition) mutable.update { it.copy(settingsPage = page, message = null) } }
    fun selectProject(project: Project) {
        if (!navigate(Screen.Definitions) { it.copy(project = project, definitions = emptyList(),
            draft = it.selections.filter { row -> row.projectId == project.id }.map { row -> row.definitionId }.toSet()) }) return
        refresh()
    }
    fun showRuns(selection: Selection) {
        if (!navigate(Screen.Runs) { it.copy(definition = selection, runs = emptyList()) }) return
        refresh()
    }
    fun showRun(run: Run) {
        if (!navigate(Screen.Details) { it.copy(run = run, tests = emptyList(), artifacts = emptyList(), reports = emptyList()) }) return
        refresh()
    }
    fun back() {
        when (state.value.screen) {
            Screen.Definitions -> projects()
            Screen.Runs, Screen.Projects, Screen.Settings -> dashboard()
            Screen.Details -> state.value.definition?.let { showRuns(it) }
            Screen.Reports -> state.value.run?.let { showRun(it) }
            Screen.Dashboard -> Unit
        }
    }
    fun refresh(more: Boolean = false) {
        val snapshot = state.value
        if (!snapshot.initialized || account == null || accountTransition) return
        val token = if (more) snapshot.next ?: return else null
        task { id, api ->
            when (snapshot.screen) {
                Screen.Dashboard -> {
                    val rows = state.value.selections
                    var failed = false
                    // Render all cached cards before the first network request.
                    for (selection in rows) {
                        dao.cache("latest:${key(selection)}")?.let { cached ->
                            val run = objects(cachedPage(cached)).firstOrNull()?.run()
                            update(id) { it.copy(latest = it.latest + (key(selection) to run), cacheTime = cached.updated) }
                        }
                    }
                    for (selection in rows) {
                        try {
                            load(id, "latest:${key(selection)}", display = { page ->
                                update(id) { it.copy(latest = it.latest + (key(selection) to objects(page).firstOrNull()?.run())) }
                            }, fetch = { api.runs(selection.projectId, selection.definitionId, null, true) })
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { failed = true }
                    }
                    if (failed) update(id) { it.copy(message = "Some latest builds could not be loaded. Check connectivity or PAT permissions.") }
                    if (rows.isEmpty()) update(id) { it.copy(message = "Add build definitions from Projects to begin.") }
                }
                Screen.Projects -> load(id, "projects:${token.orEmpty()}", { page ->
                    update(id) { it.copy(projects = ((if (more) snapshot.projects else emptyList()) + objects(page).map { row -> row.project() }).distinctBy { row -> row.id }, next = page.next) }
                }) { api.projects(token) }
                Screen.Definitions -> snapshot.project?.let { project ->
                    load(id, "definitions:${project.id}:${token.orEmpty()}", { page ->
                        update(id) { it.copy(definitions = ((if (more) snapshot.definitions else emptyList()) + objects(page).map { row -> row.definition() }).distinctBy { row -> row.id }, next = page.next) }
                    }) { api.definitions(project.id, token) }
                }
                Screen.Runs -> snapshot.definition?.let { selection ->
                    load(id, "runs:${key(selection)}:${token.orEmpty()}", { page ->
                        update(id) { it.copy(runs = ((if (more) snapshot.runs else emptyList()) + objects(page).map { row -> row.run() }).distinctBy { row -> row.id }, next = page.next) }
                    }) { api.runs(selection.projectId, selection.definitionId, token) }
                }
                Screen.Details -> {
                    val selection = snapshot.definition ?: return@task
                    val run = snapshot.run ?: return@task
                    dao.cache("artifacts:${selection.projectId}:${run.id}")?.let { cached ->
                        val artifacts = withContext(Dispatchers.IO) { objects(cachedPage(cached)).map { it.artifact() } }
                        update(id) { it.copy(artifacts = artifacts, cacheTime = cached.updated) }
                    }
                    // Independent failures still allow the other detail tab to work.
                    var testError: Exception? = null
                    try {
                        load(id, "tests:${selection.projectId}:${run.id}", display = { page ->
                            val tests = withContext(Dispatchers.IO) { objects(page).map { row -> row.testResult() } }
                            update(id) { it.copy(tests = tests) }
                        }) { api.tests(selection.projectId, run.id, run.uri) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { testError = error }
                    load(id, "artifacts:${selection.projectId}:${run.id}", display = { page ->
                        update(id) { it.copy(artifacts = objects(page).map { row -> row.artifact() }) }
                    }) { api.artifacts(selection.projectId, run.id) }
                    testError?.let { error -> update(id) { it.copy(message = safeError(error)) } }
                }
                else -> Unit
            }
        }
    }
    fun toggleDefinition(id: Int) {
        mutable.update { it.copy(draft = if (id in it.draft) it.draft - id else it.draft + id) }
    }
    fun saveDefinitions() {
        val snapshot = state.value
        val project = snapshot.project ?: return
        val rows = snapshot.draft.mapNotNull { id ->
            snapshot.selections.find { it.projectId == project.id && it.definitionId == id }
                ?: snapshot.definitions.find { it.id == id }?.let { Selection(project.id, project.name, id, it.name) }
        }
        write { dao.replaceProjectSelections(project.id, rows) }
    }
    private fun write(action: suspend () -> Unit) {
        if (accountTransition) return
        val id = generation
        val accountId = accountGeneration
        viewModelScope.launch {
            try {
                writes.withLock {
                    if (accountId != accountGeneration) return@withLock
                    action()
                    update(id) { it.copy(message = "Saved.") }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(id) { it.copy(message = "Unable to save local configuration.") } }
        }
    }
    fun addCategory(name: String) {
        val cleaned = name.trim().take(80)
        if (cleaned.isNotEmpty()) write { dao.addCategory(Category(name = cleaned)) }
    }
    fun renameCategory(category: Category, name: String) {
        val cleaned = name.trim().take(80)
        if (cleaned.isNotEmpty()) write { dao.renameCategory(category.id, cleaned) }
    }
    fun deleteCategory(category: Category) = write { dao.deleteCategory(category.id) }
    fun assign(selection: Selection, category: Long?) = write { dao.assign(selection.projectId, selection.definitionId, category) }
    fun clearCache() {
        if (accountTransition) return
        request?.cancel()
        generation++
        mutable.update { it.copy(latest = emptyMap(), projects = emptyList(), definitions = emptyList(),
            runs = emptyList(), tests = emptyList(), artifacts = emptyList(), reports = emptyList(), cacheTime = null, busy = false) }
        write { dao.clearCache() }
    }
    fun connect(organization: String, pat: String, save: Boolean) {
        if (accountTransition) return
        val org = organization.trim()
        if (!org.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) || pat.isBlank() || pat.length > 2048 ||
            pat.any { it.isWhitespace() || it.isISOControl() }) {
            mutable.update { it.copy(message = "Enter an organization name (not a URL) and a valid PAT.") }
            return
        }
        request?.cancel()
        val id = ++generation
        mutable.update { it.copy(busy = true, message = null) }
        request = viewModelScope.launch {
            var committing = false
            var saved = false
            try {
                AzureApi(Account(org, pat)).projects(null)
                if (id != generation) return@launch
                if (save) {
                    committing = true
                    accountTransition = true
                    accountGeneration++
                    update(id) { it.copy(switchingAccount = true) }
                    // Do not let navigation cancellation split persisted and in-memory account state.
                    withContext(NonCancellable) {
                        writes.withLock {
                            dao.clearAccount()
                            withContext(Dispatchers.IO) { store.save(Account(org, pat)) }
                            account = Account(org, pat)
                            mutable.update { UiState(initialized = true, organization = org, screen = Screen.Projects,
                                busy = true, switchingAccount = true, categories = emptyList(), selections = emptyList(),
                                message = "Account saved. Loading projects.") }
                            saved = true
                        }
                    }
                } else update(id) { it.copy(message = "Connection succeeded. Save to use this account.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(id) { it.copy(message = safeError(error)) } }
            finally {
                if (committing) {
                    accountTransition = false
                    mutable.update { it.copy(busy = false, switchingAccount = false) }
                } else update(id) { it.copy(busy = false, switchingAccount = false) }
            }
            if (saved && id == generation) refresh()
        }
    }
    fun disconnect() {
        if (accountTransition) return
        request?.cancel()
        val id = ++generation
        accountTransition = true
        accountGeneration++
        mutable.update { it.copy(busy = true, switchingAccount = true) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    writes.withLock {
                        dao.clearAccount()
                        withContext(Dispatchers.IO) { store.clear() }
                        account = null
                        mutable.update { UiState(initialized = true, screen = Screen.Settings,
                            busy = true, switchingAccount = true) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(id) { it.copy(message = "Unable to erase the account. Try again.") } }
            finally {
                accountTransition = false
                mutable.update { it.copy(busy = false, switchingAccount = false) }
            }
        }
    }
    fun openArtifact(artifact: Artifact) {
        val selection = state.value.definition ?: return
        val run = state.value.run ?: return
        if (!navigate(Screen.Reports) { it.copy(reports = emptyList(), reportIndex = 0) }) return
        task { id, api ->
            val reports = api.reports(selection.projectId, run.id, artifact)
            update(id) { it.copy(reports = reports) }
        }
    }
    fun chooseReport(index: Int) { mutable.update { it.copy(reportIndex = index) } }
}
