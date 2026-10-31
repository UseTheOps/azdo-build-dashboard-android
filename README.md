# Azure DevOps Build Dashboard for Android

A single-activity, responsive Jetpack Compose / Material 3 app for browsing Azure
DevOps projects and following selected build definitions.

## Build and run

Open this repository in Android Studio with Android SDK 35 and JDK 17 installed.
Sync Gradle and run the `app` configuration on an Android 8.0 (API 26) or newer
device. The checked-in wrapper uses Gradle 8.11.1; the project uses AGP 8.9.2,
Kotlin/Compose compiler 2.1.20 and KSP 2.1.20-1.0.32.

For a local debug APK, run `./gradlew :app:assembleDebug`. Compilation and device
tests have not been run as part of this implementation; verify them locally
before distribution. No Azure DevOps service credentials are required to build.

## Getting started

1. Create an Azure DevOps personal access token with **Project and Team: Read**,
   **Build: Read** and **Test Management: Read** scopes. Restrict it to the desired
   organization and use a short expiry.
2. In **Settings → Connection / account**, enter the organization name only
   (for example `contoso`, not a URL) and the PAT. **Test** checks project access;
   **Save account** also tests the connection before saving it.
3. Open **Projects**, select a project, check build definitions and tap
   **Save selection**. Use **Load more** for additional projects or definitions.
   Selection saves are atomic per project, preserve category assignments and
   preserve selected definitions that have not been loaded on the current page.
4. The **Dashboard** shows the newest queued build per selected definition,
   including its running status, result, branch and timestamp. Tap **Refresh**
   for new data, or **Runs** to browse paginated build history.
5. Select a run to browse published test outcomes and artifact names/types.
   Tap an artifact's **Preview reports** to explicitly download a supported
   report. When an archive has multiple reports, use the report-name dropdown.
6. **Settings → Dashboard & cache** creates, renames and deletes categories.
   Assign a build from its dashboard card. Deleting a category keeps its builds
   in **Ungrouped**. Clearing the cache retains selections and categories.

## Architecture and offline storage

- `MainActivity.kt`: single-activity Compose UI, narrow/wide dashboard layouts,
  account and configuration settings pages, CommonMark rendering and a
  locked-down report WebView. Scaffold safe-drawing insets and consumed inner
  padding accommodate Android 15 edge-to-edge system bars and display cutouts.
- `DashboardViewModel.kt`: MVVM observable `UiState`, navigation, selection drafts,
  refresh orchestration and monotonic request-generation guards. Navigation and
  account changes cancel/invalidate stale requests.
- `Storage.kt`: Room entities for selected definitions, categories and timestamped
  JSON caches (projects, definitions, latest builds, run pages, test results and
  artifact metadata). Cache payloads are split into 128-Ki-character rows and
  reassembled transactionally to stay below Android CursorWindow per-row limits.
  Category deletion and bulk selection saves are transactions.
- `AzureApi.kt`: HTTPS `HttpURLConnection`, platform `org.json`, API version 7.1,
  correctly encoded organization/project paths and query values. Projects,
  definitions and builds use continuation tokens; test runs and results use
  skip/top pagination until an empty page, subject to bounded safety limits.
  Build artifact metadata is returned as one API collection.

Saved pages and dashboard cards are displayed before refreshing. If the network
fails, saved data remains usable with a message and timestamp; never-loaded pages
require connectivity. Cache refresh is manual rather than background polling.
Report bodies are deliberately memory-only and not available offline. Test
previews are bounded to 20,000 results / 20 MiB. Empty builds, missing tests,
missing artifacts, authentication failures and unavailable pages have explicit
empty/error states.

## Security and report limitations

- The only Android permission is normal **Internet**. Device backup and cleartext
  traffic are disabled. Android 12+ data-extraction rules explicitly exclude all
  credential-protected and device-protected app-data domains from both cloud
  backup and device-to-device transfer, in addition to legacy backup flags.
  Tokens are masked during entry, excluded from saved
  instance state and Room, and encrypted with Android Keystore AES/GCM in private
  preferences. The app does not log tokens, server response bodies or request URLs.
- The API origin is fixed to `https://dev.azure.com/`. Saving/replacing an account,
  even with the same organization and a different PAT, erases previous selections,
  categories and caches. Disconnect also erases local credentials and account data.
  A failed connection test does not replace the active account.
- Authenticated downloads start only at the current organization's
  `dev.azure.com` URLs, rejecting traversal, encoded separators and ambiguous
  double-encoded paths. Automatic redirects are off. Artifact downloads may follow
  at most three redirects to HTTPS `vsblob.vsassets.io`, its subdomains,
  `vsblob.visualstudio.com` subdomains or Azure storage account hosts matching
  `<account>.blob.core.windows.net`, **without the PAT**, and never restore authorization.
  Other artifact URLs and redirect destinations fail safely; credentials are
  never sent to arbitrary hosts.
- Supported previews are HTML/HTM, Markdown and ZIP archives containing those
  files. Container and pipeline artifact ZIP requests use the build artifact
  endpoint with encoded `artifactName` and `Accept: application/zip`; no container
  or dedup-manifest internals are parsed. Pipeline ZIP support depends on the
  server and unsupported responses fail safely. HTML scripts, network loads,
  images, frames, forms, file/content access
  and external navigation are blocked. Markdown uses CommonMark 0.24.0 with raw
  HTML escaped and unsafe URLs sanitized, then the same isolated WebView.
  Standard headings, lists, emphasis and code blocks render; table extensions
  are not enabled and links/images remain inactive.
- Downloads are capped at 20 MiB compressed and 20 MiB expanded, with a 2 MiB
  limit per report, 1,000 ZIP entries and 30 report previews. Unsafe ZIP paths are
  not eligible for preview and no archive entries are extracted to disk.
- Binary packages, UNC/file-share artifacts, external links, pipeline artifacts
  without a supported Azure-hosted report download, JavaScript-driven reports and
  archives exceeding the limits cannot be previewed. Their names and types remain
  visible. Reports must be trusted as build output, and previews intentionally
  trade rich interactive rendering for isolation.

Dependency versions were checked against the GitHub advisory database before
being added; no matching advisories were reported. There is no test infrastructure
in the initial repository; no build, lint or test suite was run during this change.
