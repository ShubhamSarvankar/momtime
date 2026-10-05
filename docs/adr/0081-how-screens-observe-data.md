# 0081. How screens observe data: a change signal from `shared`, reads on an executor, no ViewModel, no coroutines in android sources

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review))

Decided by the Phase 3 planning session, for review. It builds on ADR 0049 and ADR 0051 (corruption in the middle of a query ends the process), ADR 0054 (the android store fails soft) and the Phase 2 pattern of a host interface and an executor per screen.

## Decision

1. **A change signal, not a stream of rows.** `shared` exposes `DataChanges` (`fun subscribe(listener: () -> Unit): Subscription`), implemented on the SQL driver's own listener for every table of the shared database, so it fires after any committed write whoever made it: a screen, a notification button, a worker, the fire path. No android source touches a generated query (`verifyNoGeneratedQueries` is unchanged), and no site has to remember to notify. The android store has no signal: its data changes only with fires and checks, and the reliability view reads it when it opens and when the shared signal fires.
2. **A screen has a plain Kotlin model** (`TodayModel`, `BuilderModel` and so on) that takes repositories, commands and a clock in its constructor, exposes one immutable state value in a Compose `mutableStateOf`, and re reads on three occasions: when created, when `DataChanges` fires, and when the screen resumes. Reads and commands run on one single thread executor (`UiEntryPoint.executor`, replaced in tests by one that runs at once); results are posted to the main thread. Models are built by `UiGraph`, which `MomTimeApplication` installs as the other entry points are installed.
3. **No `ViewModel`.** A model lives in the composition and is rebuilt after a configuration change; every read is a local query of a small database. What she is typing is kept with `rememberSaveable`.
4. **No coroutine API in android sources.** Compose brings `kotlinx-coroutines` transitively, and the rule that nothing used directly may arrive only transitively stands: the code uses the executor and `DisposableEffect`, never `launch`, `Flow` or `LaunchedEffect` with a suspend call. The bottom sheet is shown and hidden by a state flag (spiked). A structural check, `verifyNoCoroutinesInAndroid`, fails on an import of `kotlinx.coroutines` under `android/src`.
5. **Corruption in the middle of a query** still ends the process through `ProcessEnd`, after the reset notification is posted (ADR 0051). A model catches nothing: a read that meets corruption never posts a state, the process ends, and the next launch opens the fresh database. This is the case `ARCHITECTURE.md` section 2 names query listeners for; the signal's subscription dies with the process.
6. **The android store failing soft** is visible, not fatal: a model that reads it gets the fallback value, the failure is counted, and the reliability view shows the store trouble banner from the count (ADR 0054).
7. **An appearance change** reaches every live screen through `AppearanceState`, a Compose state read by `MomTimeTheme` (ADR 0074). The ring screen reads it when shown.

## Alternatives considered

- **SQLDelight's coroutine extensions and `Flow` in android.** Rejected: android would have to hold generated queries, or `shared` would have to export a flow per query, and android would import a coroutine API.
- **Notify by hand after each command.** Rejected: a missed site is a stale screen, and the driver already knows.
- **`ViewModel`.** Not needed; a fourth lifecycle to reason about beside the Activity, the composition and the process.
