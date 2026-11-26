# AGENTS.md

Guidance for coding agents working in this repository.
See `README.md` for what the feature does and how a user installs it.

## What this is

An Eclipse plug-in project that turns a running IDE into an MCP server.
Built with Maven and Tycho, pomless: the root `pom.xml` is the only pom in the repository.

## Build and test

```bash
./mvnw clean verify
```

Requires JDK 25; the Maven wrapper brings Maven 3.10.1.
Always run it from the repository root; inside a module directory the sibling bundles are missing from the reactor and the build fails.
`-pl` on one bundle still resolves the whole target platform, so there is no faster partial build.
The first run downloads the Eclipse SDK; later runs reuse `~/.m2/repository/.cache/tycho`.

## Layout

```
plugins/com.vogella.eclipse.mcp.core     provider-independent tool API, registry, extension point, shared infrastructure
plugins/com.vogella.eclipse.mcp.basic    general-purpose workspace, file, build, preference, log, command and related tools
plugins/com.vogella.eclipse.mcp.server   MCP protocol, embedded Jetty, bearer token, startup component
plugins/com.vogella.eclipse.mcp.jdt      Java model tools, declaration sweep and registry index
plugins/com.vogella.eclipse.mcp.ui       editor, view, perspective and layout tools, screenshots, preference page, UI hooks component
plugins/com.vogella.eclipse.mcp.pde      PDE tools
plugins/com.vogella.eclipse.mcp.debug    breakpoint tools, debug session tools, session registry
plugins/com.vogella.eclipse.mcp.git      EGit tools, checkout and pull request fetch
plugins/com.vogella.eclipse.mcp.gradle   Gradle tools through Buildship: refresh, run tasks, task list
plugins/com.vogella.eclipse.mcp.ide      IDE-only tools: open in editor, compare, launch shortcuts, project import
plugins/com.vogella.eclipse.mcp.p2       provisioning tools, repositories, install, update, headless trust
plugins/com.vogella.eclipse.mcp.web      web page tools: a Chromium driven over the DevTools Protocol
features/com.vogella.eclipse.mcp.feature
tests/com.vogella.eclipse.mcp.core.tests    the tools, headless
tests/com.vogella.eclipse.mcp.server.tests  the HTTP endpoint, driven by a real MCP client
target-platform/com.vogella.eclipse.mcp.target
update-site/com.vogella.eclipse.mcp.repository
releng/update-composite-site.sh
```

## Conventions

Java 25, tabs for indentation, the Eclipse formatter defaults.
Javadoc is concise: a sentence or two on what a class or method does, no `@param` or `@return` for anything obvious from the name.
Inline comments are rare and explain the non-obvious why, never the what.
Markdown uses one sentence per line.
Commit messages have a short subject and a body of a few sentences saying what changed and why.
PR descriptions are a few sentences of prose on what changed and why, without an implementation plan, test plan or bullet-list changelog.
A workaround for an Eclipse bug is recorded in `docs/platform-bugs.md` with what was observed, the file and line, and whether it is filed upstream, so it can be removed once the platform is fixed.

## Adding a tool

1. Implement `com.vogella.eclipse.mcp.core.IMcpTool`.
2. Contribute it through the `com.vogella.eclipse.mcp.core.tools` extension point in the `plugin.xml` of the bundle whose dependencies it needs.
3. To replace a tool another bundle contributes under the same name, for example with a richer version of it, give the contribution a higher `priority`; the highest wins while both are installed.
4. Add a test. `McpToolRegistryTest` already checks that every registered tool has a name, a description and an input schema that parses as JSON.

## Architecture rules

**`com.vogella.eclipse.mcp.core` stays clean.**
No reference to the MCP SDK, Jetty or any UI bundle, so tools are written and tested against a small stable API, run headless and in RCP applications, and do not change when the protocol library does; that is also why it has its own JSON reader and writer in `com.vogella.eclipse.mcp.core.json`.
When a tool outside the UI bundle needs UI integration, core declares a hook and the immediate DS component `McpUiComponent` registers the implementation; the ui bundle has no activator and no `org.eclipse.ui.startup` extension, as `LogClearedHandlers` and `UiDispatch` do.
A failing handler never turns a completed operation into a failed call.
Core remains provider-independent and must not depend on basic.
The server name, version and instructions come from `McpServerDescription`, which reads the `com.vogella.eclipse.mcp.core.server` extension point each time the server starts; `withMissingTools` runs over the composed text, so `eclipse_*` tools named in a product's instructions also get the note on tools that are not installed.

**The server starts through a declarative service, not `org.eclipse.ui.startup`.**
`McpServerComponent` is an immediate DS component that publishes `McpServerService`, so an RCP application runs the same path as the IDE.
The bundle requires the `osgi.component` extender so that an SCR-less framework fails to resolve it loudly instead of never starting the server.
`OSGI-INF/*.xml` is generated by PDE's DS builder and committed; regenerate it in the IDE after changing an annotation, since `tycho-ds-plugin` keeps a stale descriptor. `McpServerComponentTest` guards it.
The `@Reference` to `IExtensionRegistry` is never read and must stay: the tool list is built once at start and would otherwise be short for the session.
The `@Reference` to the instance `Location` targets `url=*`: instance preferences throw until a workspace is chosen, so without it the workspace chooser case never starts the server.
`activate` only schedules the reconciliation job and never touches `Platform` or the preferences: with `-data` it runs nested in SCR creating the `ContentTypeManager`, and loading `Platform` there kills the IDE on start.
Reconciling lives in `McpServerLifecycle`, shared with the preference page and serialized by a lock, not a job scheduling rule, because starting the server can open the workspace and `Workspace.open` fails its `beginRule` inside a foreign rule.

**Writing tools say so.**
Most tools are read-only.
A tool that writes says so in capitals in its own description, the only place the model sees it, and defaults to a dry run where one makes sense.
No tool opens a dialog.

**Threading.**
Tool calls arrive on Jetty worker threads; never call `Display.syncExec` from one.
Queue UI work through `UiThread.exec`, which runs inline when already on the UI thread; a caller that runs a batch of tools inside one Display runnable would deadlock otherwise.
A UI request waits `CallBudget.uiWaitSeconds`: the longer of the tool's own wait and the `uiWaitSeconds` preference, capped by what is left of the call, which `McpToolAdapter` records through `CallBudget.enter` so a tool with several UI hops still answers in time.
It is then withdrawn (`completeFrom` skips cancelled futures), and `Outcome.errorResult` answers a JSON error carrying `withdrawn` so a client can retry.
`UiThread.timed`, which `eclipse_run_workbench_command` uses to keep running and log the outcome, keeps its caller's timeout.
A preference write fires its listeners on the writing thread, and editors touch widgets in them, so the basic preference tool goes through `UiDispatch` for writes that UI listeners react to.
Marker reads and JDT searches are safe off the UI thread and need no workspace lock.

**The call timeout.**
The server aborts a call after `McpPreferences.getCallTimeout()`, 30 seconds by default, read per call by `McpToolAdapter`.
A tool that can outlast it starts a job and returns a handle (`eclipse_build` and `eclipse_get_build_status`); any bounded wait goes through `CallBudget` and answers with the handle plus a `waitNote`.
`eclipse_build` defaults `timeoutSeconds` to 25; the basic provider cannot read the server preference, so the two are kept in step by hand.
On timeout the adapter cancels the monitor first, and `abandon` records calls that keep running and reports them to the next caller, because they hold locks that look like a slow IDE.

**Dialogs.**
Every long running operation a client drives has a dialog on it somewhere: p2's unsigned content prompt, the workspace chooser on restart, `org.eclipse.debug.ui`'s "Errors in Workspace" status handler, the compare "no differences" message.
Do not let it be raised on a client path; answer it and report what was answered.
Before adding a tool that runs project code or touches the installation, look for a prompting `IStatusHandler` on that path.
`eclipse_run_workbench_command` probes the Display while it waits, which a modal dialog's nested event loop answers, and returns `dialogOpen` with the title once a new dialog (`Shells.newDialog`: modal, or a titled JFace window, which a product can block on without it being modal) other than a `ProgressMonitorDialog` shows on two probes in a row; otherwise it caps its wait and answers `timedOut`.
That probe is `DialogWatch`, which `eclipse_press_key` shares for a synthetic key whose listener opens a dialog, so it answers `dialogOpen` too instead of timing out.
Either way the future keeps running and a late result goes to the Error Log; its `IExecutionListener` is what makes `handlerFinished` trustworthy.

**IDE-only tools live in `com.vogella.eclipse.mcp.ide`.**
`com.vogella.eclipse.mcp.ui` does not require `org.eclipse.ui.ide`, `org.eclipse.compare`, `org.eclipse.debug.ui` or `org.eclipse.jgit`, because an RCP product that adds it would otherwise pull them and the Debug perspective in.
A tool that needs one of them goes into the ide bundle, which reaches `UiThread`, `Workbenches` and the other helpers through the `x-friends` export of `com.vogella.eclipse.mcp.ui.internal`.

**Workbench access goes through `Workbenches`, not `PlatformUI`.**
`PlatformUI` only knows the 3.x workbench; `Workbenches` falls back to the E4 workbench.
A tool that needs editors, views, perspectives or commands refuses with `Workbenches.noIde()`.

**Optional dependencies live in one class each.**
`CssStyling` (e4 CSS engine) and `GitContent` (jgit, in the ide bundle) are imported optionally and callers catch `LinkageError`; never let one of their types into a signature a tool touches.
`CocoaKeys`, `DisplayScaling`, `NativeAppKit`, `NativeComboList`, `NativeDialogs`, `NativeForeground`, `NativeScreen`, `NativeStacking` and `NativeWindowState` reach SWT internals (`DPIUtil`, `cocoa.NSApplication`, `gtk.GTK`, `win32.OS`) by name, so the bundle stays cross platform.

**Answers.**
Every list-returning tool honours `maxResults` and reports `total` and `truncated`.
A picture is attached with `McpToolResult.withImage`, never as base64 inside the JSON.
Anything derived from files refreshes first through `WorkspaceSync.refresh`, since clients edit through their own shell; stale markers are worse than none.
`eclipse_find_references`, `eclipse_get_call_hierarchy`, `eclipse_find_implementations` and `eclipse_list_declarations` pay that refresh on every call, which in a workspace of hundreds of projects is most of the call, so they take `refresh` (default true, reported as `refreshed` and `refreshMillis`) to let a client that already refreshed skip it; the default stays on.
Read what the answer needs off a widget before the action that may dispose it, and report disposal as a fact (`shellClosed`); a completed operation must never come back as a failed call.
Never put a Java element's `toString()` into a message; use `getElementName()`, `getFullyQualifiedName()` or `JavaModelSupport.describe`.
Build a lookup key in one helper, as `FindReferencesTool.locationOf` does; two spellings silently match nothing.

**"The most recent" is ambiguous with more than one client.**
The build, test run, provisioning and command registries are per IDE, so those tools consult `ClientSessions` and refuse the implicit default when a second client is connected.
`ActiveSessions` counts `Mcp-Session-Id` headers and drops a session on its DELETE, and counts a client on 2026-07-28 twice, since it has no session and two copies of one client cannot be told apart, so such a client always has to pass the id; with no provider (headless, tests) the count is one.

**The web bundle has no third party dependency.**
`com.vogella.eclipse.mcp.web` speaks the Chrome DevTools Protocol over `java.net.http.WebSocket` and never downloads a browser; Playwright for Java would need a Node.js driver per platform inside the bundle and a browser download on first use.
`CdpConnection.HTTP` bypasses any proxy, because the browser listens on loopback and an IDE's system proxy would not reach it.
A started browser picks its own port (`--remote-debugging-port=0`) and writes it to `DevToolsActivePort` in its temporary profile, which is the only race-free way to learn it.
Refs from `eclipse_web_snapshot` live in `window.__mcpRefs` in the page rather than in DOM attributes, so the page is never changed; `window.__mcpIds` keeps an element's ref across snapshots, and each snapshot starts numbering at a block `WebBrowser.reserveRefs` set aside beforehand for every element of the page and reports how far it went, so a ref is not reused, even after a navigation, a browser restart or a snapshot that never answered (unless the page grew by more than the 200 spare refs between the count and that snapshot), and an old one is refused rather than landing on another element.
The started browser keeps Chromium's DevTools origin check (no `--remote-allow-origins`), since the JDK WebSocket sends no `Origin`; `--no-sandbox` is added only when running as root, where Chromium refuses to start otherwise.
The tools run one at a time through `WebPage.exclusive`: a call locates its element and acts on it in separate commands, and the page, its refs and `acceptDialogs` are shared by every client.
Starting a browser is bounded by `CallBudget` as well as the settle wait, so a slow first start answers before the call timeout.
JavaScript dialogs block the page until answered, so `WebBrowser` answers each at once and the acting tool reports it under `dialogs`; a confirm or prompt is dismissed unless the call passes `acceptDialogs`, and a leave-page prompt is accepted, which the descriptions say because it discards unsaved input.
Every DevTools command waits at most what is left of the call, and the settle wait leaves `ANSWER_SECONDS` for reading the page afterwards, so the answer is never what the call timeout kills.
`WebToolsTest` drives a real headless browser and is skipped where none is installed or the one found cannot start; the surefire `argLine` sets `com.vogella.eclipse.mcp.web.noSandbox` because CI agents and containers often cannot set up Chromium's sandbox. Install Chrome or Chromium on the Jenkins agent, or the web tools go untested there.

## Tests and CI

- `com.vogella.eclipse.mcp.core.tests` requires the ui bundle and runs headless: nothing there may call a ui tool that needs a workbench, and never `eclipse_restart` or `eclipse_exit` (the latter ends the test JVM). `ShutdownGuardsTest` checks them by declaration.
- No test may run `eclipse_update` or `eclipse_install`. `ProvisioningGuardsTest` asserts their registration, that `eclipse_update` defaults to a dry run and has `acknowledgeSelfUpdate`, and that the descriptions announce what they do. `eclipse_install` has a dry run but does not default to it.
- `callsEveryRegisteredTool` in the server tests only sees basic, server and jdt tools; a green run is not protocol coverage of the other bundles.
- Tests never write the real token: the surefire `argLine` sets `-Dcom.vogella.eclipse.mcp.tokenDirectory` to `target/mcp-token`, and a test fails if that redirect is lost.
- JUnit comes from the Eclipse SDK; test bundles require `junit-jupiter-api` `[5.14.0,6.0.0)`, since the target also ships JUnit 6, which the junit5 provider cannot launch. Do not add a Maven location for it.
- Generated output that carries semantics, `OSGI-INF` descriptors above all, can diverge from source silently, which is why `eclipse_run_tests` reports `descriptorGeneration` and `buildBeforeLaunch` for every plug-in test run. A never-read `@Reference` field can be an ordering guarantee, not dead code.
- GitHub Actions do not build branches or PRs; run `./mvnw clean verify` locally before pushing.
- `Jenkinsfile` runs `./mvnw clean verify` for every branch and PR on a Jenkins with the tool `Java 25 Temurin` on the `built-in` agent, without `xvnc` because the suite needs no display and one would mask a tool that started needing a workbench, and without `-Dmaven.test.failure.ignore=true`. The agent still needs the GTK 3 libraries, because SWT loads them without a display and the ui tool tests fail with `libgtk-3.so.0` missing.

## Gotchas

Do not undo these without understanding why they are there.

### Build and packaging

- The root pom sets `project.build.outputTimestamp` to the build time: Tycho takes the qualifier from it, and the super POM of Maven 3.10 and later pins it to 1980, which gives every build the same version and p2 never sees an update.
- `.mvn/maven.config` sets the Tycho version, which `.mvn/extensions.xml` and the root pom read, and `tycho.pomless.aggregator.names`, without which `update-site/` is not an aggregator and the build fails.
- The feature includes Jetty ee11, the MCP SDK, Jackson 3, networknt, reactor, jspecify and `jakarta.servlet-api`, and leaves out `slf4j.api`, which the host IDE ships.
- The target pins Jackson 3.2.4 and reactor-core 3.9.0 next to the SDK: its pom brings Jackson 3.1, which networknt 3.0.8 does not resolve against, and a reactor release candidate.
  The server imports `jakarta.servlet` `[6.1,7)`, which a plain SDK does not provide, so without the bundle an install from the update site alone fails.
- Jetty imports are pinned to `[12.1.12,13)`, the floor `jetty-ee11-servlet` needs.
- `com.vogella.eclipse.mcp.ui` is the feature's branding plugin; `featureImage` is 32x32 (source `icons/eclipse-mcp-server.png`), and there is no `about.mappings` because Tycho does not substitute `{0}`.
- `org.w3c.dom.css` comes from the JRE's `jdk.xml.dom` module and is not imported; an optional import the target cannot resolve would be a compile error.

### Server

- The bearer token is a plain owner-only file in user scope, `~/.eclipse/com.vogella.eclipse.mcp.server/token`, shared by all workspaces because the port is shared; `.prefs` files are world readable. It is written through a temporary file and an atomic move so a second IDE never sees it missing. An older workspace token is adopted once and renamed `token.migrated`.
- Fixed-port mode keeps the configured port: if taken, the server stays down after `awaitPort()` waited 10 seconds for its release, and `McpServerService.getLastError()` says why. Moving silently would break every configured client. The preference page offers `McpServerService.findFreePort` and switches only when the user accepts.
- Every successful startup saves its actual listening port as `lastPort` in workspace-local `port.properties` next to `endpoint.json`. Fixed-port mode always binds only to the configured preference and never reads the saved port for binding. The opt-in `com.vogella.eclipse.mcp.server.ephemeralPort` property best-effort reuses the saved port, except when it equals the configured fixed port; if the saved port is occupied, Jetty immediately retries with port 0. Unreadable, invalid or unwritable port state is ignored and logged without blocking startup. `endpoint.json` remains authoritative, and clients must rediscover its URL after a restart. `McpServerLifecycle` deliberately avoids restarting an already-running ephemeral server when preferences are unchanged, because moving its port would drop existing clients; fixed-port changes still trigger a restart.
- `McpServerService.start` binds the port before it loads the tools, and `ReadyGate` holds requests until they are loaded. Claude Code gives up reconnecting after five refused attempts, about 15 seconds, so loading first would leave it disconnected after most restarts. A held request waits at most `READY_WAIT_MILLIS`, 25 seconds, which is kept below Claude Code's 30 second connection timeout by hand.
- `BundleJsonSchemaValidator` clears the context class loader, or networknt fails with `FileNotFoundException: classpath:draft/2020-12/schema`; any MCP client built inside the IDE needs the same (`McpServerServiceTest.schemaValidator()`).
- The MCP SDK gets an explicit `jsonMapper` and `jsonSchemaValidator`; `ServiceLoader` discovery across bundles is fragile.
- One endpoint serves both protocol generations: `ProtocolRouter` sends a POST that names a version in `_meta['io.modelcontextprotocol/protocolVersion']`, or that carries no `Mcp-Session-Id` and a stateless `MCP-Protocol-Version`, to the stateless server of 2026-07-28, and everything else, `initialize`, GET and DELETE included, to the session-based one, which clients fall back to. Dropping the session server would cut off every client that does not speak 2026-07-28 yet.
- The stateless server is `io.modelcontextprotocol.modern`, imported `[2.2,2.3)` because the SDK may still change it in minor releases. It is built after the tools load, so its transport talks to a `PendingRequestManager` that `ReadyGate` guards. Both servers list the tools sorted by name.

### Workspace, build and log tools

- `eclipse_get_problems` never starts a build: it refreshes, waits for a running build and reports `upToDate` false with `staleness` when auto-build is off. Starting a build is `eclipse_build`'s job.
  With a marker it defaults to every severity and reports `buildsSinceMarker`, counted workspace-wide by a `POST_BUILD` listener that `BuildCounter` registers on first use, with `staleness` when it is 0; a check of a change that was not built otherwise reads as clean.
- Everything slow runs inside the build job, the refresh included: `BuildRegistry.Request` carries it, `scopes` limits it, and `refreshMillis` and `buildMillis` are reported apart.
- `BuildManager` swallows builder exceptions, so `collectLogged` adds platform log errors and warnings from the build's time window to `builderFailures`; over-reporting is intended.
- `eclipse_get_log_entries` parses `.metadata/.log` rather than listening to `ILog`, which misses everything before the listener and from earlier sessions. `PlatformLogFile` is `x-friends` to the tests.
- UI freezes are logged as WARNING, so the log defaults to `severity: all` while problems default to `error`; do not align them.
- `eclipse_mark_log` returns a byte position that `eclipse_get_log_entries` takes as `marker`; prefer it to `since`, which compares clocks and cannot see a rotation (`markerStale`).
- `eclipse_clear_log` deletes the log file, which Equinox reopens per write, and verifies by writing and reading back an entry (`stillLogging`, `LogStateToolsTest.clearingLeavesTheLogWritableAndReadable`).
- `eclipse_set_preference` has no qualifier allowlist on purpose, since `eclipse_write_file` and `eclipse_apply_css` walk around one anyway; `previousValue` in the answer is the record. Auto-build goes through `IWorkspaceDescription.setAutoBuilding`.
- `eclipse_run_command` has no directory allowlist on purpose: a refused client runs the command in its own shell and the IDE loses the audit trail. `directory` is required, absolute and must exist; it is not a security boundary.
- `eclipse_revert_files` writes the HEAD blob through `IFile.setContents` with `KEEP_HISTORY` so the discarded content is in local history, reports `localHistory` per file, resets a staged or unmerged index entry to HEAD as well (`unstaged`), and refuses untracked files by name.
- `eclipse_search_text` collapses nested-project duplicates by `getLocationURI()` plus offset into `alsoVisibleAs` and reports `duplicatePathsCollapsed`.
- `eclipse_set_project_state` defaults to `dryRun`, needs an explicit selection, and refuses without `force` when `getReferencingProjects()` has open dependents. `closingTogether` resolves a batch as a fixpoint, identically for dry and real runs.
- `eclipse_list_projects` reads `.project` from disk for closed projects and reports `natureSource`; "no natures" for a closed project is a lie.
- `FrameworkChanges` records hot installs and bundle substitutions, and `eclipse_restart` then defaults to `-clean` and says why under `cleanReason`; stale registry caches otherwise break restored editors. Do not make `-clean` unconditional.
- `eclipse_substitute_bundle` packs the manifest whether or not `bin.includes` names it and expands patterns such as `*.so`, because SWT's fragments rely on both; a jar without either left an IDE that did not start. Entries that match nothing are reported as `unmatchedIncludes`.
- In a shared installation `eclipse_substitute_bundle` writes the user area's bundles.info, which simpleconfigurator ignores once the shared file's time differs from the user area's `.baseBundlesInfoTimestamp`. `SharedBase` mirrors that rule; substitute updates the timestamp, before it writes bundles.info, only when the two files are identical apart from the substituted lines or `acceptSharedChanges` is set, since a user area's own installs and an update of the shared installation look the same from here.
- `eclipse_substitute_bundle restore` acts on the whole installation, shared by every IDE started from it; pass `bundle` to restrict it. `build.properties` is read through `java.util.Properties` so CRLF continuations work, `pack` refuses roots outside the project, and unreadable directories land in `unreadable` (`SubstituteBundlePackTest`).

### JDT

- A search built from an element narrows to projects that can see it, so `eclipse_find_references` resolves every copy of a type through `JavaModelSupport.findTypes` and ORs across them.
- For a field, `READ_ACCESSES` and `WRITE_ACCESSES` run as separate searches, so the counts do not sum to `total`; an initializer is only in `byKind`, a compound assignment is `readWrite`. Do not make them agree.
- Organize imports, rename and clean-up need the `org.eclipse.jdt.ui` preference node seeded, or JDT throws deep inside; `ensureCodeStylePreferences` fills defaults only.
- `RenameTool` calls `initializeValidationData` and runs through `IWorkspace.run`, and refuses binary elements, which JDT's processor dereferences as null.
- `JavaModelSupport.findType` prefers source over a binary type from build output.
- `SourceText` is the one offset-to-line index of the jdt bundle; do not add another, or line numbers drift between tools.
- `SearchMatch.getResource()` names the owning project for a jar match; use `JavaModelSupport.describeLocation` and never `match.getResource().getFullPath()`.
- `eclipse_run_tests` takes the test kind from the x-friends `TestKindRegistry.getContainerTestKindId`, because `JUnitLaunchConfigurationDelegate.preLaunchCheck` rechecks it and refuses a JUnit 6 project launched as junit5; a copy of that decision would drift from the delegate.
- `eclipse_clean_up` deliberately depends on the discouraged `CleanUpConstants` and `*CleanUpCore` (no public alternative; `CleanUpRefactoring` drags in the UI). `RemoveUnusedImportsTool` stays on public API. Clean-up options are a tree, so `CleanUpEntry.companions` enables the parents a leaf needs.
- Callees in `eclipse_get_call_hierarchy` come from the bindings of the method's AST (`Callees`), not the search engine, which has no callee query; the parse uses a focal position so only that body is resolved. Anonymous and local class bodies are not the method's calls, lambda bodies are.
- `eclipse_find_implementations` decides overriding with the x-friends `MethodOverrideTester` from `jdt.core.manipulation`, which substitutes type arguments; `IType.findMethods` compares parameter signatures literally and misses `scale(Double)` implementing `scale(T)`.
- `eclipse_delete` is an LTK resource delete, so PDE's participants do not fire and `plugin.xml` and `Export-Package` are not updated; the description says so and the answer reports dangling evidence. Do not drive JDT's internal delete processor.
- `eclipse_list_declarations`:
  - `RegistryIndex.LITERAL_REFLECTION` needs `,` or `)` after the closing quote, so a concatenated `Class.forName` stays `undecidable`.
  - `basedOn` never demotes a verdict, and a declaration with `registryEvidence` is never `dead` (`ListDeclarationsToolTest`).
  - `bundleclass://` URIs in `.e4xmi` files are registry evidence (`indexApplicationModel`), since `eclipse_delete` acts on these verdicts.
  - A `$` name is indexed under every enclosing name with `namedType`; `Outer.Inner` is not evidence.
  - `extensionPointsWithoutSchema` is filtered to the projects asked about.
  - Extension point schemas are parsed here, not through PDE's `x-friends` schema model.
- `PackageExports` parses `Export-Package` directives itself (commas outside quotes), because PDE strips them; a search is authoritative only for unexported packages or ones whose `x-friends` are all in the workspace.
- The export-removal guard blocks on `importedBy` and only lists Require-Bundle dependents under `mightAlsoUseIt`.

### PDE

- Platform mismatch reads `Eclipse-PlatformFilter` through `PlatformFilters`; the project name heuristic is only the fallback, and the reason says which was used.
- `eclipse_set_bree` sets the JRE container itself (`setJreContainer`), because `IBundleProjectDescription.apply()` does not touch `.classpath`, and repairs the container even when the header matches. It writes all of `getComplianceOptions()` and compares only `COMPLIANCE_KEYS`.
- `com.vogella.eclipse.mcp.pde` needs `Bundle-ActivationPolicy: lazy` for its `BundleContext`.
- `eclipse_edit_manifest` refuses a manifest with any attribute or directive outside `version`, `x-internal`, `x-friends`, `bundle-version`, `visibility` and `resolution` (including `uses:`), because `apply()` rewrites the whole header and would drop them.

### UI

- `eclipse_import_project` uses the `x-internal` `SmartImportJob` deliberately, which is why it lives in the ide bundle rather than basic. It always sets the directories to import (avoiding a modal question), uses `getImportProposals` as the dry run, and restores the auto-build state the job can leave off.
- `Reconcilers` reads the reconciler by field name the way JDT's `EditorTestHelper.joinReconciler` does (including the upstream typo `fIninitalProcessDone`); an unreadable one counts as busy, never idle. Lookups are cached per class, a probe stops after `BUDGET_MILLIS`, `UiSettle` sleeps at least as long as the last probe, and the wait is capped by `CallBudget.maxWaitSeconds()` and reports `clamped`.
- `eclipse_wait_for` probes with `getPart(false)`, since a wait must not create the part it waits for, and `find` skips virtual rows that are not populated, since reading one fires `SetData`. A timeout is a normal answer with each condition's last observation; a UI-busy probe marks only the widget conditions. It refuses on the UI thread, where it would block what it waits for.
- `eclipse_wait_until_settled` is a heuristic whose description names what it cannot see, and a test asserts that wording.
- `eclipse_set_sash` sends the Sash the two Selection events of a person's drag instead of calling `setWeights`, one with `detail` `SWT.DRAG` and the release where that one stopped, so SashForm's minimum size and other sash listeners apply as for a person. A single release that SashForm clamps only writes the weights, and a `layout()` instead would move the other controls.
- `suppressCaret` defaults to on and the answer reports `caretsSuppressed`.
- Root capture can return uniform pixels without error (GTK4, compositing), so the uniform check triggers the `Control.print` fallback. Do not add an environment check to `unsupportedReason`; the environment cannot tell the cases apart.
- `DeviceScale` owns capture on scaled displays: root capture fills an image built from `ImageData` and is read at device zoom, widget print draws into an `ImageData`-built image and never a 1:1 canvas plus `Transform` (see `docs/platform-bugs.md`). `zoom` is derived from the returned pixels, and `deviceZoom` and `belowDeviceZoom` report a loss. Unpainted canvas is magenta.
- On Windows `ScreenPixels` reads the screen with `BitBlt` into a bitmap of the area's device pixels, because `GC.copyArea` returns points and an SWT image sized by the display zoom clips a monitor scaled higher. `ScreenMap` converts between points and pixels by each monitor's two rectangles, in integers, rather than by a zoom, since SWT's coordinate system mappers lay out a second monitor differently and a wrong conversion returns a plausible picture of the wrong area.
- `eclipse_get_widget_tree` reports `bounds` (parent relative), `boundsInShell` (shell client area, what a shell capture shows) and `boundsInDisplay` (the only one a synthetic click can use).
- A computed CSS value is read back off the widget and proves nothing, so `CssStyling.styles` also reports `declared` and `origin` from the cascade.
- The CSS engine API is not binary stable across releases, and the IDE here runs a newer platform than the target, so `CssStyling` reaches `parseStyleSheet`, the rule count, the error handler (`divertErrors`) and the computed style reflectively under every known spelling. Check a new call with `javap` against both jars, and check the answer on a newer IDE, not only that the call returned.
- `eclipse_apply_css` reaches `ThemeEngine.resetCurrentTheme()` and `getCSSEngines()` reflectively and looks the engine up by name through `IEclipseContext`; re-applying the theme first is what `reset` does.
- `eclipse_open_compare` always returns a `DiffNode` and reports `identical`, so the "no differences" dialog never opens.
- `McpUiComponent.deactivate` calls `VisibilityTool.restoreIfHidden`, so disabling the server cannot leave the IDE hidden.
- `IWorkbench.close` calls `WorkbenchAdvisor.preShutdown` whatever the guard cleared, so a product can raise its own exit confirmation inside it. `CloseWatch` probes during `eclipse_exit` and `eclipse_restart`: with `force` it presses Yes or OK by JFace's own labels, otherwise it records `heldByDialog` for the next call, because the answer is already gone by then.
- `eclipse_restart` uses `restart(true)`, which keeps `-data`. `IWorkbench.restart` is a cancellable close, so `force` closes every window's dirty parts itself, a `false` return is reported as `previousRestartFailed` on the next call, and `--launcher.oldUserArgsStart` in `eclipse.commands` marks a real relaunch. It refuses while a modal dialog is open.
- `eclipse_restart` with `flightRecording` or `vmArgs` writes the exit data itself and calls `restart(false)`, with `-vmargs` last: `restart(true)` appends `-data` behind it, which the JVM refuses. `--launcher.appendVmargs` keeps `eclipse.ini`'s VM arguments, which `-vmargs` alone replaces. Lines are not quoted, since exit data splits on newlines. `RelaunchVmArguments.jvmRefusal` tries the arguments on a throwaway JVM first, because a refused one leaves only the launcher's error dialog; they go behind the IDE's own from `eclipse.vmargs`, or a combination refused only together passes. `probeArguments` filters that property, which holds the launcher's `-jar` or class path that would run Eclipse, and a recording, agent or file-writing option that would overwrite its file or hang the probe. An agent or file-writing option in `vmArgs` stays out of the probe too, for the same reason, and `jvmCheck` names it as not tried. The probe runs `-version`, so `ENDS_THE_JVM`, `REPLACES_THE_PROGRAM` and `CLASS_PATH` are the only guard against those arguments, which it would pass and the IDE not come back. A second restart is refused while one is pending, since its arguments would land behind the first one's `-vmargs` and reach the JVM. `stackDepth` is its own argument and a `-XX:FlightRecorderOptions` in `vmArgs` is refused alongside a recording, because the JVM keeps only the last one and the depth falls back to 64.
- A native dialog has no SWT shell, so `NativeDialogs` finds a file picker or message box through the Win32 owner chain, and `eclipse_list_ui_targets`, `eclipse_dismiss_dialog` and `returnOnDialog` go through it; without it a held handler only times out.
- `NativeScreen` tests a grid of points with `WindowFromPoint` for `otherWindows`, because a z-order walk reports cloaked windows as covering the area.
- A Win32 Combo's drop-down is a native window with no SWT control, so `NativeComboList` reads its rows and captures it, and `eclipse_combo_list` opens it through the public `setListVisible`, never the pointer.
- `AccessibleNames` reads the `accessible` field rather than calling `getAccessible`, which would create a native object for every control the tree walks.
- On Windows, `NativeForeground` attaches to the foreground input queue for a raise through the foreign function API, since SWT's `OS` no longer has `AttachThreadInput`, detaches in a `finally`, skips windows `IsHungAppWindow` reports, and reports `foregroundMethod`. Read foreground back through `NativeForeground.isForeground`, never `Display.getActiveShell`, which on GTK reports the request before the compositor has answered and on Cocoa is not `NSApplication.isActive`; `eclipse_press_key` gates on it after polling for late focus through `ForegroundWait` from the calling thread, never the UI thread, where the wait would hold back the focus it waits for. Unverified on Windows.
- `XTestInput` keeps one X connection for the life of the process and opens it on the UI thread. `XOpenDisplay` holds Xlib's process-wide lock while it waits for the server, and GDK takes that lock while it holds a server grab, so a connection opened on another thread can hang the IDE and the tool call together.
- On native Wayland, `MutterRemoteDesktop` drives `eclipse_click` and `eclipse_drag` through mutter's `org.gnome.Mutter.RemoteDesktop` session: relative moves corrected by `getCursorLocation`, which Wayland reports only over this IDE's windows and never during a drag, so the moves run off the UI thread (the IDE sees them only once its event loop dispatched them) and a drag reads the pointer only after the release.
- `eclipse_drag` moves the pointer through XTest, wiggles it on each point, and resolves the first leg before the press: SWT's X11 drag detection holds the UI thread for up to 500 ms after the press, so a UI call there delays the first move past it, and GTK starts its drag partway along the line, so a drop target only reacts to motion after that.
- `eclipse_screenshot` reports a non-integer scale, and `crispWidth` snaps to a whole divisor.

### Debug

- `eclipse_get_launch_output` reads the `IStreamMonitor` buffers of the launch's processes, which the platform fills from process start, so it needs no listener and shows output printed before the first call; `eclipse_debug_launch` points to it in `outputNote` when the program has already ended.
- `eclipse_debug_launch` `waitForOutput` (regex over both streams) and `waitForPort` (localhost) poll within `CallBudget` and answer `ready`, `terminated` with the exit code and output tail, or `timedOut`, never an error; either one makes the default suspend wait 0, which would only delay a running program. Run-mode launches this server started are registered too, so the output tool reads them; run-mode launches a person started are not adopted.
- `eclipse_list_launch_configurations` finds `.launch` files of types no plug-in contributes by scanning open projects and the debug state `.launches` folder, because the launch manager drops them silently, and lists them with `launchable` false and `missingType`. `eclipse_save_launch_configuration` is a dry run by default, merges only the attributes it is given into an existing configuration of the same type, and refuses to replace one of another type without `allowTypeChange`.
- `eclipse_debug_launch` `environment` is merged over the configuration's own variables on the working copy that is launched, with `ATTR_APPEND_ENVIRONMENT_VARIABLES` set, and the working copy is never saved, so a user's saved configuration keeps its environment.
- Terminate acts on the `ILaunch`, so a `mode: run` launch without a debug target can be ended; `DebugSupport.noTarget` tells "run mode" from "has ended".
- `eclipse_hot_code_replace` attaches through a helper JVM (`HotSwapAttacher`) because self attach needs a startup flag, and packs `HotSwapAgent` into a jar so its `Instrumentation` lives in the system class loader. `HotSwapSupport.existing` finds it there by name; keep `HotSwapAgent` dependency free and its one method stable. Classes are loaded through the bundle before redefinition. `HotCodeReplaceToolTest` attaches for real. Compile replacement classes with the IDE's `org.eclipse.jdt.core.compiler.batch` jar, since javac and ecj name lambdas differently.

### Git

- The git bundle requires EGit and JGit optionally, and every tool checks `EGit.isAvailable()` first.
  The requirements state the minimum 7.0.0 and no upper bound: the resolver drops an optional requirement whose range excludes the installed EGit without a log entry, and every git tool then refuses. `EGit.unavailable()` tells that case from a missing EGit.
  Verifying a class can load the JGit types its methods use, and a failure there loses the tool with an error at every start; `RevertFilesTool` and `FetchPullRequestTool` keep their JGit code in a nested class for that reason, and `GitToolsWithoutEGitTest` loads every git tool with JGit and EGit hidden.
- The ide bundle requires `org.eclipse.jgit` with `resolution:=optional` and references it only from `GitContent`, so an IDE without EGit loses only `eclipse_open_compare`'s `revision` argument.

### Gradle

- The gradle bundle requires Buildship (`org.eclipse.buildship.core`, which re-exports the Tooling API) with `resolution:=optional`, and every tool that starts Gradle checks `Buildship.isAvailable()` first; `GradleToolsWithoutBuildshipTest` loads every tool with Buildship and `org.gradle.*` hidden.
  Only `GradleOperations` names a Buildship or Tooling API type, and no signature there or in the tools carries one, so a missing Buildship never breaks class loading.
- Gradle work runs as a `GradleRuns` job answered with a `runId`, which `eclipse_get_gradle_run` polls and cancels, the way `eclipse_run_command` does; a wait goes through `CallBudget`.
  `eclipse_list_gradle_tasks` keeps its in-flight model request in `PENDING`, so a call that outlasts the wait finds the list on the next one.
- A project counts as Gradle by `Buildship.NATURE`, checked before any Buildship call, so a plain project is refused by name.
- Tools that start Gradle say RUNS in capitals, since build scripts, tests and applications execute.

### p2

- The update site is a composite whose single child changes per release. `Provisioning.describeRepositories` refreshes metadata before resolving, by default; do not make it opt-in. `Provisioning.describe` refreshes the artifact side through `refreshArtifacts` after the metadata before install, or the download fails with "No repository found containing".
- A check scoped to `getInstallableUnitSources` looks where the installed version lives, which never holds a newer one, so `widenToAllRepositories` retries against every enabled repository and says so. The retry is a new `UpdateOperation`, since one keeps each unit's possible updates from its first resolution and re-resolving the scoped one returns the same empty answer.
- A self update replaces the bundles running it and has left an IDE without a server: `UpdateTool.selfUpdates` requires `acknowledgeSelfUpdate`, and `EndpointFile.markStopped` leaves a record. The real fix, driving it from outside the feature, is not written.
- `HeadlessTrust` answers p2's trust prompts instead of a modal dialog. `trustUnsigned` defaults to true because locally built repositories are unsigned and `eclipse_add_repository` already lets a client pick sources. Keep `persistTrust` false, never return `trustAlways`, and record every prompt; the list is capped at `MAX_PROMPTS` with `trustPrompts`, `trustPromptsTotal`, `trustPromptsTruncated` and the verdict in `trustedUnsigned`.
- `eclipse_install` resolves against only the repository the unit was found in unless `contactAllSites` is set, because the default `ProvisioningContext` lets the planner swap installed dependencies for versions from other sites, which has left an IDE unable to start. `PlanEffects.of` lists the installed units a plan replaces, removes or adds a second version beside; without `allowChangingInstalled` an install of a new unit refuses on any of them and an upgrade on a removed unit or a second version, since an upgrade replaces its own bundles, and `eclipse_update` reports them under `plan`.
- `eclipse_install` refuses repositories the IDE is not configured with. That is friction, not a security boundary; do not replace it with a preference or build guards on top of it.
- `eclipse_restart` lives in the ui bundle without a p2 dependency so a half applied update stays recoverable, and every provisioning result carries the previous configuration timestamp for Installation History.

### Portability

The repository is developed and tested on Linux; anything platform shaped is unverified elsewhere.

- `FileLocations` is the only place that turns a `Location` URL into a `Path`: `url.getPath()` breaks on drive letters and `%20`, and `Path.of(url.toURI())` throws on unencoded spaces.
- Use `FileLocations.isWindows()` rather than `os.name`, and `Path.startsWith` rather than a string prefix.
- `CommandRegistry` asks `chcp` once for the OEM code page, since `native.encoding` reports the ANSI one, and decodes output per line trying UTF-8 first.

## Verifying UI work

A UI change is not verified by reading the JSON.
Capture the image and look at it, and crop to bounds from `eclipse_get_widget_tree` rather than by eye, so you know which widget you are looking at before the first fix.
A capture downscaled by a fraction is blurry and looks like a rendering bug.

## Releasing

Every push to `main` publishes through `.github/workflows/update-site.yml`, the only GitHub workflow; `gh workflow run update-site.yml` republishes by hand.
It builds `main` with the tests, so a red build publishes nothing, copies the p2 repository into `releases/<built version>/` on `gh-pages`, regenerates the composite with `releng/update-composite-site.sh --only "$version"`, adding `--retain` with the build the live composite lists, and force-pushes a single orphan commit.
`--retain` keeps the build the live composite listed on disk, unlisted, for one more publish: GitHub Pages and its CDN switch files one at a time, so an install that read the old composite would otherwise get 404 for every jar.
Use `--only`, not `--keep 1`: `sort -V` does not sort qualifiers as p2 does.
Its `concurrency` group lets a newer pending run replace an older one.

Leave versions alone for ordinary changes; the `yyyyMMddHHmm` qualifier makes each build an update.
Bump `Bundle-Version`, `feature.xml` and `pom.xml` together when something worth naming lands.

The site is `https://vogellacompany.github.io/eclipse-mcp-server/`.
Never edit `compositeContent.xml`, `compositeArtifacts.xml`, `p2.index` or `index.html` on `gh-pages` by hand, and never overwrite an existing `releases/<version>/` with different content, since p2 caches aggressively.
Pushing under `.github/workflows/` needs a token with the `workflow` scope or an SSH remote.
Self updating through the running server needs a human reachable to close a modal dialog, since `eclipse_restart` refuses while one is open.
Tycho caches remote p2 repositories for 60 minutes under `~/.m2/repository/.cache/tycho`; to see a fresh release, delete the cache directory for the host, since `-Dtycho.p2.transport.min-cache-minutes=0` is not enough.
