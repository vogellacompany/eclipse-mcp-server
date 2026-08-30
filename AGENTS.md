# AGENTS.md

Guidance for coding agents working in this repository.
See `README.md` for what the feature does and how a user installs it.

## What this is

An Eclipse plug-in project that turns a running IDE into an MCP server.
Built with Maven and Tycho, pomless: the root `pom.xml` is the only pom in the repository.

## Build and test

```bash
mvn clean verify
```

Requires JDK 25 and Maven 3.9 or newer.
Always run this from the repository root.
Running it from inside a module directory resolves that module alone and fails, because the sibling bundles are then missing from the reactor.

The first run downloads the Eclipse SDK for the target platform, so expect it to take a while.
Later runs reuse the Tycho cache under `~/.m2/repository/.cache/tycho`.

There is no faster partial build worth using: `mvn verify -pl` on a single bundle still resolves the whole target platform.

## Layout

```
plugins/com.vogella.eclipse.mcp.core     tool API, registry, extension point, workspace tools
plugins/com.vogella.eclipse.mcp.server   MCP protocol, embedded Jetty, bearer token
plugins/com.vogella.eclipse.mcp.jdt      Java model tools, declaration sweep and registry index
plugins/com.vogella.eclipse.mcp.ui       editor, view, perspective and layout tools, compare, screenshots, preference page, startup hook
plugins/com.vogella.eclipse.mcp.pde      PDE tools
plugins/com.vogella.eclipse.mcp.debug    breakpoint tools, debug session tools, session registry
plugins/com.vogella.eclipse.mcp.git      EGit tools, checkout and pull request fetch
plugins/com.vogella.eclipse.mcp.p2       provisioning tools, repositories, install, update, headless trust
features/com.vogella.eclipse.mcp.feature
tests/com.vogella.eclipse.mcp.core.tests    the tools, headless
tests/com.vogella.eclipse.mcp.server.tests  the HTTP endpoint, driven by a real MCP client
target-platform/com.vogella.eclipse.mcp.target
update-site/com.vogella.eclipse.mcp.repository
releng/update-composite-site.sh
```

## Rules that are not obvious from the code

**`com.vogella.eclipse.mcp.core` must stay clean.**
No reference to the MCP SDK, to Jetty or to any UI bundle.
The bundle is meant to stay a candidate for contribution to the Eclipse Platform, and the split exists only for that reason.
It also has no JSON library, which is why it carries the small reader and writer in `com.vogella.eclipse.mcp.core.json`.
When a core tool needs something to happen in the UI, it goes through a hook the UI side registers, the way `eclipse_clear_log` empties the Error Log view through `LogClearedHandlers`: core declares the interface, `McpUiPlugin.start` registers the implementation, and the handler's answer is folded into the tool's result. A failing handler must never turn a completed operation into a failed call.

**Most tools are read-only, and the exceptions are deliberate.**
`eclipse_organize_imports` and `eclipse_format` modify the file they are given, `eclipse_write_file` creates and replaces files, `eclipse_set_target_platform` replaces what the workspace compiles against, `eclipse_build` runs builders, `eclipse_get_problems` triggers a build when auto-build is off, `eclipse_run_workbench_command` does whatever the named command's handler does, `eclipse_manage_window` opens and closes windows, `eclipse_log_status` writes into the Error Log, `eclipse_install_bundle` installs or replaces a bundle in the running framework or copies a jar into dropins, `eclipse_checkout` and `eclipse_fetch_pull_request` change the working tree through EGit, and the p2 tools `eclipse_install` and `eclipse_uninstall` change the installed software itself.
The debugger tools change things without touching the workspace: `eclipse_set_breakpoint` edits the breakpoint list, `eclipse_debug_launch` starts a process, `eclipse_debug_evaluate` runs an expression inside the debugged program, and `eclipse_debug_control` steps and terminates it.
Everything else must not write, and no tool may open a dialog or perform a refactoring.
A new tool that writes has to say so in its own description, because that is the only place the model sees it.

**A command's dialog hazard is handled at the timeout, not by inspection.**
Whether a command handler opens a modal dialog is not knowable in advance, and a modal one holds the UI thread inside the execute call forever, which no timeout from outside can interrupt.
`eclipse_run_workbench_command` therefore caps its wait, answers `timedOut` with pointers to `eclipse_list_ui_targets` and `eclipse_dismiss_dialog`, leaves the future uncancelled so nothing is dropped, and logs what the command went on to do.
An `IExecutionListener` on the resolved command records which verdict fired, so the timeout answer can tell a dialog still holding the handler from one that already reached success or failure; that listener is what makes `handlerFinished` trustworthy rather than an inference from the return.

**Threading.**
Tool calls arrive on Jetty worker threads.
Never call `Display.syncExec` from one; hand work to `asyncExec` and wait on a future with a short timeout, the way `GetEditorContextTool` does.
Queue through `UiThread.exec` rather than `getDisplay().asyncExec` directly: a tool can now be called from another tool, and `eclipse_run_script` with `atomic` runs a whole batch inside one Display runnable, where queueing and then waiting on the future blocks the very thread that would run it.
`UiThread.exec` runs the work inline when it already is the UI thread, and every entry point of `UiThread` does the same, which is what turned a ten second deadlock into a twelve millisecond step.
Marker reads and JDT searches are safe off the UI thread and need no workspace lock.
The server aborts any call that has not finished within the configured call timeout, 30 seconds by default.
`McpToolAdapter` reads `McpPreferences.getCallTimeout()` per call, so a changed preference applies without a restart.
A tool that can outlast that timeout must not block on it; start a job and hand back a handle, the way `eclipse_build` and `eclipse_get_build_status` do.

**Optional JDK and platform packages are isolated in one class.**
`CssStyling` holds every reference to the e4 CSS engine, `GitContent` every reference to jgit, and `FlightRecording` every reference to `jdk.jfr`, all imported optionally so callers catch `LinkageError`. A JVM or an IDE without them then costs a refusal that names what is missing, rather than a failure somewhere unrelated.
`DisplayScaling` is the same shape for SWT's own internals: `org.eclipse.swt.internal.DPIUtil` has changed shape across releases and `org.eclipse.swt.internal.gtk.GTK` exists on one window system only, so a direct call would make this bundle GTK-only. Both are reached by name there, and an IDE without them costs a null in one field of `eclipse_get_display_info` rather than a failing tool.

**A preference write fires its listeners on the writing thread.**
`EclipsePreferences.put` calls every listener synchronously, and editors answer a colour change by touching widgets, so a write from a Jetty worker thread ends in "Invalid thread access", an error dialog from `SafeRunnableDialog`, and editors that never repaint.
`SetPreferenceTool` writes through `UiDispatch`, the same hook shape as `LogClearedHandlers`: core declares it, `McpUiPlugin` registers `UiThread.EXECUTOR`, and headless the work runs inline.
Any new core tool that writes something UI listeners react to goes through it as well.

**Every list-returning tool honours `maxResults` and reports `total` and `truncated`.**
A new tool that returns a list without those fields is incomplete.

**Anything derived from files must refresh first.**
A client edits through its own shell, so the workspace does not know about those edits until `WorkspaceSync.refresh` runs.
`eclipse_get_problems` does it by default and reports `upToDate`; the two editing tools refresh the file they touch.
Reporting stale markers as if they were current is worse than returning nothing.

## Adding a tool

1. Implement `com.vogella.eclipse.mcp.core.IMcpTool`.
2. Contribute it through the `com.vogella.eclipse.mcp.core.tools` extension point in the `plugin.xml` of the bundle that owns it.
3. Put it in the bundle whose dependencies it needs, so that the layering above survives.
4. Add a test. `McpToolRegistryTest` already checks that every registered tool has a name, a description and an input schema that parses as JSON.

## A hazard that cost a night, and what it taught

A UI plug-in test run failed for hours with no tests and three log lines about a null command manager. The cause was two deleted `@Reference` fields in an uncommitted local edit to `BindingToModelProcessor`. Those fields are never read in Java; they exist so that declarative services registers two sibling processors first. Deleting them looks like removing dead code and removes an ordering guarantee, and the failure surfaces five layers away as a workbench that will not start.

Two things follow for this repository. Generated build output that carries semantics, `OSGI-INF` descriptors above all, can diverge from source without any compiler saying so, which is why `eclipse_run_tests` reports `descriptorGeneration` and `buildBeforeLaunch` whether or not anything went wrong. And an answer that looks the same whether a launch ran current or stale artefacts is what turns a five minute problem into a night; that is the same reasoning behind reading launch attributes back out of the configuration rather than echoing what was requested.

## Platform bugs

When a workaround here exists because Eclipse is wrong rather than because we
are, record it in `docs/platform-bugs.md`: what was observed, the file and line,
and whether anything is filed upstream.
The point is that these get fixed in the platform eventually rather than
accumulating as silent workarounds nobody dares remove.

## Continuous integration

`.github/workflows/build.yml` runs `mvn clean verify` on every push and pull request, under `xvfb-run` because the Tycho surefire run starts a real Equinox.
Before it existed the whole test suite ran only when somebody remembered to, and a regression could reach a release build undetected.
`release.yml` has a `concurrency` group so two dispatches cannot race the gh-pages publish.

`Jenkinsfile` is the same validation for an Eclipse-style Jenkins: `mvn clean verify`, publish the JUnit results, archive the test IDE's own log and the p2 repository.
It assumes the tool names an Eclipse JIPP instance provides, `apache-maven-latest` and `temurin-jdk25-latest`; on any other Jenkins those are what to change.
It deliberately does not wrap the build in `xvnc`, unlike the platform repositories: this suite runs with no `DISPLAY` at all, which was measured rather than assumed.
A display would only mask a tool that had quietly started needing a workbench.
It also does not pass `-Dmaven.test.failure.ignore=true`, because a red build is the point and this repository has already shipped a suite that ran zero tests while reporting success.

## Gotchas already paid for

Do not undo these without understanding why they are there.

**`.mvn/maven.config` sets `tycho.pomless.aggregator.names`.**
Pomless Tycho only treats `bundles,plugins,tests,features,sites,products,releng` as aggregator directories.
Without the override, `update-site/` is not recognised and the build fails with "Child module .../update-site/pom.xml does not exist".

**JUnit comes from the Eclipse SDK, not from Maven.**
The target platform already contains `junit-jupiter-api` 6.1.0 through the SDK feature.
Adding a Maven location for JUnit is redundant, and the resulting 5.x bundles lose to the SDK's 6.x ones anyway.
Note the bundle symbolic names are `junit-jupiter-api` and so on, not the `org.junit.jupiter.api` that older Orbit builds used.

**The bearer token is persisted in user scope, the port is not negotiated.**
`TokenStore` keeps the token in `UserScope`, `~/.eclipse/com.vogella.eclipse.mcp.server/token`, with owner-only permissions, so one token serves every workspace this user opens.
Workspace scope was wrong: the port is one preference with the same default everywhere, so per-workspace tokens are several secrets behind one address and a client registered against one workspace is rejected by the next, which looks exactly like the token rotating.
It is a plain file rather than a preference because a `.prefs` file is world readable.
Written through a temporary file and an atomic move: truncating in place leaves a window with no token, and a second IDE starting inside it mints a new one and invalidates every client of the first.
A workspace token from an older build is adopted once and renamed to `token.migrated`, because a file still called `token` beside the live `endpoint.json` is what a human reads while diagnosing.
The server never falls back to a different port when the configured one is taken; it stays down and records the reason in `McpServerService.getLastError()`, which the preference page shows.
Silently moving to another port would break every configured client, which is worse than not starting.

**The tests must never write the real token, and the build is what stops them.**
User scope is shared with the developer's own running IDE, and the server test bundle regenerates the token, so every `mvn verify` replaced the token that IDE was serving and orphaned its clients.
`TokenStore.location()` honours `-Dcom.vogella.eclipse.mcp.tokenDirectory`, the surefire `argLine` in the root pom points the tests at `target/`, and a test fails if that redirect is ever lost.

**A JDT search built from an element carries that element as its focus.**
`IndexSelector` then narrows the search to the projects that can see it, so a workspace wide question bound to one copy of a type silently answers nothing about the others, with `truncated` false.
SWT declares `org.eclipse.swt.graphics.Image` once per window system, so `eclipse_find_references` resolves every copy through `JavaModelSupport.findTypes` and ORs the members of one overload across them.
An `OrPattern` carries no focus, which is why the old merged-overload search happened to cover everything; splitting it per overload for `byMember` is what exposed this.

**A wait that outlives the call timeout loses the handle it could have returned.**
`eclipse_build` advertised a 3600 second maximum under a 30 second call timeout, so a real build came back as "did not finish within 30 seconds" and the `buildId` went with the error.
Any tool that blocks bounds its wait through `CallBudget` and answers with the handle plus a `waitNote`.

**Field reads and writes are counted from separate searches, and the counts do not sum to `total`.**
`eclipse_find_references` runs `READ_ACCESSES` and `WRITE_ACCESSES` in addition to `REFERENCES` when the target is a field, and matches them up by path and offset.
A field initializer is a write access but not a reference, so it appears in `byKind` and never in `matches`; a compound assignment is both, and is tagged `readWrite`.
Do not "fix" the mismatch by making the numbers agree, it is the truth of what JDT reports.

**`OrganizeImportsTool` seeds JDT UI preferences.**
JDT reads the import order, the on-demand thresholds and the type filter from the `org.eclipse.jdt.ui` preference node, which only that plug-in registers.
Headless, or before the UI plug-in has started, the lookup returns null and the operation fails with a `NullPointerException` deep inside `CodeStyleConfiguration` or `TypeNameMatchCollector`.
`ensureCodeStylePreferences` sets the node id and fills the default scope only where nothing is set, so a user or project setting always wins.

**A refactoring change has to be initialised before it can be performed.**
`createChange` returns a change whose validation state is empty, and `PerformChangeOperation` then fails with "TextFileChange has not been initialialized".
`RenameTool` calls `initializeValidationData` first and runs the operation through `IWorkspace.run`.

**`RenameTool` needs the same jdt.ui preference node as `OrganizeImportsTool`.**
The rename processors read `JavaManipulation.getPreference`, which throws `IllegalArgumentException` out of `ProjectScope.getNode` when the node id is unset.
Renaming a field hits it through `GetterSetterUtil`; renaming a type does not, so this fails for one kind of rename and not another.

**`BundleJsonSchemaValidator` clears the context class loader on purpose.**
The networknt schema library reads its bundled meta-schemas through the context class loader and only falls back to its own when there is none.
Inside Equinox the context class loader cannot see them, so without this the server fails to start with `FileNotFoundException: classpath:draft/2020-12/schema`.
Any MCP client built inside the IDE needs the same treatment, as `McpServerServiceTest.schemaValidator()` shows.

**The MCP SDK is used with explicit `jsonMapper` and `jsonSchemaValidator`.**
Letting the SDK fall back to `McpJsonDefaults` makes it depend on `ServiceLoader` discovery across bundles, which is fragile under OSGi.

**`eclipse_get_log_entries` parses `.metadata/.log` instead of listening to `ILog`.**
A listener registered when the bundle starts cannot see anything logged before that, and loses everything from previous sessions, which is exactly where the interesting UI freezes and builder exceptions already are.
The file is the complete record and its `!ENTRY` / `!SUBENTRY` / `!MESSAGE` / `!STACK` format keeps multi status nesting and stack traces intact, so parsing it costs less than it looks.
`PlatformLogFile` is exported to the test bundle through `x-friends`, so the parser can be tested against fixtures without the tool reading an arbitrary caller-supplied path.

**A UI freeze is logged at severity WARNING, not ERROR.**
`org.eclipse.ui.monitoring` uses `IStatus.WARNING`, so `eclipse_get_log_entries` defaults to `severity: all` while `eclipse_get_problems` defaults to `error`.
The two defaults differ on purpose; do not align them.

**`eclipse_get_problems` never starts a build.**
It used to, and with auto-build off JDT turned the requested incremental build into a batch compile of every open project: `JavaBuilder.buildAll` and `BatchImageBuilder` in the stack, thirty seconds to read some markers.
Scoping the build to one project was not the fix; a tool that reads markers should not compile anything.
It refreshes, waits for a build already running through `waitForBuild`, and leaves starting one to `eclipse_build`.
`upToDate` is therefore false whenever auto-build is off, and `staleness` says why. Two tests that asserted the old promise were rewritten rather than worked around.

**Everything slow belongs inside the job, the refresh included.**
The refresh first ran before the job was scheduled, so `wait: false` still blocked for its whole duration and an unscoped refresh of a large workspace blew the 30 second call timeout before the async path was ever reached.
It also refreshed the workspace root even when one project was named.
`BuildRegistry.Request` now carries the refresh, `scopes` limits it to the named projects, and `refreshMillis` and `buildMillis` are reported separately because the refresh can cost more than the build.

**`eclipse_build` returns a handle rather than blocking to completion.**
`BuildRegistry` runs the build as a job under the workspace build rule and keeps the last 20 outcomes, so a build longer than the call timeout is polled through `eclipse_get_build_status` instead of dying with the request.
`timeoutSeconds` defaults to 25 to sit under the default 30 second call timeout; core cannot read the server bundle's preference without breaking the layering, so the two numbers are kept in step by hand.

**A builder that throws does not fail the build, so `builderFailures` reads the log.**
`BuildManager` runs builders inside a `SafeRunner`: the exception is caught and logged, `IProject.build` returns normally, and `BuildRegistry.collect` finds nothing.
This was shipped broken once and caught only against a real workspace, where a clean build of `JavaEclipseProject` reported `builderFailures: []` while logging `JavaBuilder handling CoreException` in the same second.
`collectLogged` therefore also reports platform log errors and warnings from the build's time window.
It over-reports by design, because anything logged during the window is included; calling a broken build clean is worse.
The unit tests can only check that entries from before the build are excluded, since making a builder throw on demand is not worth the fixture. The positive case is verified against a real workspace.

**`eclipse_set_preference` has no allowlist, and adding one back would not buy anything.**
It had one, of four qualifiers, and every extension of it was friction rather than safety: `eclipse_write_file` writes any `.settings/*.prefs`, and the `IEclipsePreferences` blocks `eclipse_apply_css` accepts write any qualifier at all.
A guard that two tools of the same server walk around only ever stopped the caller with a legitimate need.
That is measured, not argued: the theme session wrote `SHOW_MEMORY_MONITOR` into the `org.eclipse.ui` node from a theme stylesheet, a qualifier the list refused, and it went through.
The hazard it was aimed at is real and is handled by reporting instead: a wrongly set compiler or formatter preference is invisible and long-lived, and `previous` in the answer is the only record of what it was.
Auto-build goes through `IWorkspaceDescription.setAutoBuilding`, not through a raw write of `description.autobuilding`, which is the usual way to get it subtly wrong.

**`eclipse_run_command` has no directory allowlist either, and removing it was the same lesson twice.**
It was gated behind a list of roots configured in the preference page, empty by default, so the tool answered "running commands is switched off" until somebody filled it in.
What that produced was not a command that did not run. The client has its own shell: it ran the same command outside the IDE, in the same account, with the same rights, and this server lost the only thing it was adding, which is that the build and the workspace are one picture.
So the guard cost the visibility rather than the capability, and it took the audit trail with it: a command run through this tool is recorded, pollable through `eclipse_get_command_output` and bounded by a directory the caller had to name, while the one that went around it is none of those.
`directory` stays required, absolute and checked to exist. That is not a boundary and must not be described as one; it stops a command inheriting whatever working directory the IDE happens to have.
The preference key and `CommandRoots` are gone rather than left unread, because a preference the page still shows and nothing enforces is worse than none.

**`eclipse_revert_files` writes through the workspace so the discarded content survives.**
`git checkout HEAD -- <file>` destroys the working copy with no way back.
Writing the HEAD blob through `IFile.setContents` with `KEEP_HISTORY` puts what was discarded into Eclipse's local history instead, so a caller who reverted the wrong file can get it back from *Compare With > Local History*.
That is the whole reason this tool exists rather than a `eclipse_run_command` invocation, and it is why the answer reports `localHistory` per file: a file outside the workspace is written directly and has no such safety net, and saying so is the difference between a caveat and a surprise.
An untracked file is refused by name rather than deleted. It has no HEAD content, deleting it is unrecoverable, and it is the last thing a caller asking to revert would expect; `RevertFilesToolTest` asserts the file is still there afterwards rather than only that the call refused.

**The reconciler is reached by name, and an unreadable one counts as busy.**
`AbstractReconciler` starts as a job and then hands its work to a plain daemon thread, so neither the job manager nor a fence posted to the Display can see it, and semantic highlighting lands after everything observable has gone quiet.
That was measured from outside before it was understood here: a harness saw two runs of one scenario differ by 1051 pixels of italics with every wait reporting idle.
`Reconcilers` reads it the way JDT's own performance tests do in `EditorTestHelper.joinReconciler`: `SourceViewer.fReconciler`, then `AbstractReconciler.fWorker`, then `isDirty` and `isActive` on the package-private worker, plus `JavaReconciler.fIninitalProcessDone`, whose spelling is a typo upstream and has to be matched exactly.
That an Eclipse test suite does it this way is evidence the approach works, not that it is supported; every name is internal and can change in any release.
So a reconciler that cannot be read is reported busy, never idle. The direction matters more than the reading: a renamed field then costs a settle that never succeeds, which is loud, instead of one that succeeds too early, which is silent and is the whole failure this exists to prevent.

**`eclipse_wait_until_settled` is a heuristic and its own description is the guardrail.**
It is honest about what it cannot observe, and a test asserts that wording rather than the behaviour, because the wording is what a model reads before deciding to trust it.
The blind spot has already moved once, from the reconcilers to whatever plain background threads remain, so the test checks that something is still named rather than naming a particular case.
`settlePixels` catches the one thing none of the three signals can: a repaint already in flight, which is neither queued, nor a job, nor a reconciler.
Comparing two captures does not need to know why, which is exactly why it is worth having beside the mechanisms that do.
Its two outcomes are a diagnosis and the description says so, because the case that motivated it turned out not to be the case it caught.
A whole-window comparison differing by a few thousand scattered pixels was assumed here to be a paint in flight; it converged on the first pair every time, and the real cause was one part laid out a few pixels lower, dragging everything below it and looking like noise spread over the window.
So `converged false` means the screen never stopped, and `converged true` with an image that still differs means it stopped at a different LAYOUT. Waiting cannot fix the second, because the waiting already worked.

**`suppressCaret` defaults to ON, and the two failures are not symmetric.**
SWT draws and blinks the caret itself, so no window system setting reaches it, and two captures of a focused editor differ by the caret alone.
Off, that reads as a real difference and is silent. On, a capture meant to show the caret loses it and the answer says `caretsSuppressed`, which explains itself.
It shipped defaulting to false on the argument that a capture taken to look at the caret must show it; that argument weighed one case against nothing and ignored which failure a caller could diagnose.

**Platform mismatch is read from `Eclipse-PlatformFilter`, not from the project name.**
`PlatformFilters` parses the manifest header as an OSGi filter and matches it against `osgi.ws`, `osgi.os` and `osgi.arch`.
The name heuristic is the fallback for projects without the header, and the reason string says which of the two was used.
Do not promote the heuristic to the primary signal; it works for Eclipse's own naming convention and misfires everywhere else.

**Closing a project that others reference creates errors instead of removing them.**
`SetProjectStateTool` reports `openDependents` from `IProject.getReferencingProjects()`, which covers both the JDT build path and PDE required bundles, and refuses without `force`.
It also defaults to `dryRun`, and requires an explicit selection, so that no call can close the whole workspace by omission.

**`IBundleProjectDescription.apply()` does not touch `.classpath`.**
It writes the manifest header and nothing else, so `eclipse_set_bree` points the JRE container at the new environment itself with `JavaRuntime.newJREContainerPath`.
This was assumed to be automatic and the test caught it; do not remove `setJreContainer` on the belief that PDE reconciles the project.

**Only compliance, source and target are written, though the environment offers more.**
`IExecutionEnvironment.getComplianceOptions()` returns further options whose values do not round-trip through `IJavaProject.getOption`, so comparing all of them meant a project could never be seen as up to date and every run reported a change.
Write and compare exactly `COMPLIANCE_KEYS`.

**`com.vogella.eclipse.mcp.pde` needs `Bundle-ActivationPolicy: lazy`.**
It looks up `IBundleProjectService` from its own `BundleContext`, which is null while the bundle is merely resolved.
The tool falls back to PDE's own context and then to a readable error, rather than a `NullPointerException`.

**The ui bundle is required by `com.vogella.eclipse.mcp.core.tests`, and that run is headless.**
It is there so the registry tests cover the ui tools' names and schemas, and so the argument handling that happens before the UI thread is reached can be tested at all.
Nothing in that bundle may call a ui tool that needs a workbench, and nothing there may ever call `eclipse_restart` or `eclipse_exit`, both registered in that run even though the smoke test that would reach them lives elsewhere.
`eclipse_exit` is the worse of the two: a restart would at least come back, while an exit ends the JVM the suite is running in, and `ShutdownGuardsTest` covers both by reading their declarations and by checking that they refuse without a workbench, never by calling them for real.
Loading a ui tool class headlessly is safe; every one of them refuses with "There is no running workbench" rather than touching `PlatformUI`.

**The provisioning tools can be covered only by their declarations.**
No test may run `eclipse_update` or `eclipse_install`, because a passing test would have changed the IDE it ran in.
`ProvisioningGuardsTest` asserts what a careless edit would silently drop: that they are registered, that `eclipse_update` still defaults to a dry run and still has `acknowledgeSelfUpdate`, and that the descriptions still announce what they do and that an unknown repository is refused rather than added.
Note the asymmetry it documents rather than fixes: `eclipse_update` is a dry run by default and `eclipse_install` has no dry run at all.

**A call that outlives its timeout keeps its thread.**
`McpToolAdapter` cancels the progress monitor first, which is what actually stops a cooperative tool; `Future.cancel(true)` only interrupts, and a tool blocked on the workspace lock or in native code keeps running whatever anyone does.
Nothing can fix that, so `abandon` records those calls, logs them, and tells the next caller how many are outstanding.
The point is that the leak stops being invisible: each abandoned call holds locks that block later builds and refreshes, and the symptom otherwise looks like a slow IDE.

**`callsEveryRegisteredTool` does not call every tool.**
`com.vogella.eclipse.mcp.server.tests` requires only core, server and jdt, so the ui, pde and p2 tools are not registered in that headless run and the smoke test cannot see them.
That is deliberate for `eclipse_restart`, which must never be invoked by a test, but do not read a green smoke test as protocol level coverage of the other bundles.

**p2 caches repository metadata, and a cached miss reads as success.**
`UpdateOperation` resolves against whatever is cached, so a newly published build is invisible until something invalidates it, and the answer is the same "no updates found" a current IDE gives.
`Provisioning.describeRepositories` refreshes before resolving, by default, and reports each repository's timestamp.
Refresh the metadata manager only. An update check never reads artifact metadata, and `ColocatedRepositoryTracker` refreshes both, which is why `RepositoryTracker` is not used here.
When units are named, `getInstallableUnitSources` says which repositories can supply them, and only those are refreshed and resolved against through `setProvisioningContext`; a broad call with no units stays broad on purpose.
This matters most in the self-update loop, where the tool is used immediately after a publish. Do not make the refresh opt-in to save a round trip; a check that quietly lies is worse than a slow one.
The update site is a composite whose child location changes per release rather than accumulating, so it is the composite document itself that has to be re-read.

**`IWorkbench.restart` is a cancellable close, so a prompt can veto the restart.**
It routes to `close(RETURN_RESTART, false)`, and that `false` means `saveAllParts` prompts for every dirty part; a veto returns `false` and the JVM stays up.
There is no forced-restart API, so `eclipse_restart` with `force` discards the work itself, closing dirty editors without saving, rather than leaving it to a prompt that nobody is looking at on a background IDE.
The guard asks the model for every dirty part in every window, which is the set the platform prompts for, not the active page's editors: a part this side did not count cleared the guard and then stalled the close in an invisible dialog.
The boolean `restart` returns is the only signal that any of this went wrong, and it used to be discarded under an answer that had already claimed success, which is how a restart could fail silently for a whole session.
The answer cannot report the outcome, since the server dies with the IDE, so it reports what was requested and a failed attempt is carried by the next call as `previousRestartFailed` and logged.
A process that came up from a relaunch carries `--launcher.oldUserArgsStart` in `eclipse.commands`, which is the cheapest way to tell a real relaunch from a restart that never happened.

**`IWorkbench.restart()` relaunches without the original command line.**
Use `restart(true)`. The no argument form drops `-data`, so the IDE comes back up showing the workspace chooser and waits for a person, which is exactly what an unattended restart must not do.

**`getReferencingProjects()` answers about now, not about after the call.**
It reports the projects that are open at the moment it is asked, so `SetProjectStateTool` closing a cluster refused every member whose dependents were themselves in the same batch, and the refusal described a state that would not exist once the call returned.
`closingTogether` resolves the batch as a fixpoint before acting, and only dependents that will still be open afterwards block anything.
It has to iterate rather than subtract the selection once, because removing one project from the set can block another, and it must resolve the same way for a dry run as for a real one, where nothing has been closed yet either.

**A closed project cannot be asked what natures it has, and answering "none" is a lie a client cannot detect.**
`IProject.getDescription` fails while closed, so `ListProjectsTool` reads `.project` from disk and reports `natureSource` as `model`, `projectFile` or `unknown`.
Without it a client classifying projects by nature gets a different answer for the same workspace depending on which projects happen to be open, which was measured as 73 hits one hour and 185 the next on an unchanged workspace.
Closed projects are exactly the ones a cleanup client needs to classify, so this is not an edge case.

**A reflective load only counts as resolved when the literal is the whole argument.**
`RegistryIndex.LITERAL_REFLECTION` requires a `,` or `)` after the closing quote.
Without it `Class.forName("registry." + suffix)` matches as a resolved literal named `registry.`, and the one case that must stay `undecidable` is reported as live.
The fixture in `ListDeclarationsToolTest` contains exactly that expression; it caught this, and it is the reason the test exists.

**In `eclipse_list_declarations`, `basedOn` never demotes a verdict.**
It shipped doing exactly that and it reported 14 live classes in `org.eclipse.ui.ide` and `org.eclipse.ui.workbench` as dead, through three mechanisms: a schema whose constraint is conditional (`org.eclipse.ui.decorators` names `ILabelDecorator` while every `lightweight="true"` decorator implements `ILightweightLabelDecorator`), the `IExecutableExtensionFactory` form where `basedOn` describes the product and not the named class, and a class named as its own `basedOn`, since `getAllSupertypes` does not include the type itself.
`basedOn` is a single-valued hint that real schemas cannot always express, so an unsatisfied constraint is a flag for a person and not evidence of a stale entry.
Unverifiable is not refuted, and unsatisfied is not refuted either. The invariant to hold on to: a declaration with `registryEvidence` can never be `dead`, which `ListDeclarationsToolTest` asserts.

**Export visibility decides what a workspace search can prove, and PDE's model throws it away.**
`ExportPackageDescription.getName()` reports the package with its directives stripped, so a public package and an `x-internal` one come back indistinguishable, which is exactly the part the question needs.
`eclipse_get_bundle_info` reports the directives, and `PackageExports` parses the header itself in the jdt bundle rather than taking a PDE dependency for two directives.
The header parser has to split on commas outside quotes, because an `x-friends` list is a quoted comma-separated value inside a comma-separated header.
A search is authoritative only where the package is not exported, or where every bundle its `x-friends` list names is a project in this workspace; everything exported plainly is unprovable no matter how many zero results you collect.

**A scoped result must not carry a workspace-scoped caveat.**
`extensionPointsWithoutSchema` is filtered to the projects that were asked about.
It once reported the same four points for two projects that contributed to none of them, on results with zero undecidable declarations, so the caveat described a limit those answers did not have and invited distrust of a fully judged result.

**The extension point schemas are parsed here rather than read through PDE.**
`org.eclipse.pde.internal.core.schema` and `.ischema` are exported `x-friends:="org.eclipse.pde.ui"`, so `SchemaAttribute` would resolve at runtime but is a discouraged access at compile time and a dependency PDE may break in any release.
What is actually needed is `kind="java"` and `basedOn` on one element, which is far smaller to own than PDE's schema model.
If a real case turns up that needs schema includes or inherited attributes, that is when this tradeoff is worth revisiting.

**A hidden IDE must not be able to outlive the thing that can unhide it.**
`eclipse_set_ide_visibility` can take the window off the screen and the taskbar, where no menu can bring it back, so `McpUiPlugin.stop` calls `VisibilityTool.restoreIfHidden`.
Disabling or uninstalling the server must not be the moment the IDE becomes unrecoverable.

**JGit is an optional dependency and every reference to it lives in `GitContent`.**
`org.eclipse.jgit` is in the target platform to compile against and is required with `resolution:=optional`, so an IDE without EGit still installs the feature and loses only the `revision` argument of `eclipse_open_compare`.
That only works while the jgit imports stay inside that one class: the caller catches `LinkageError`, which is what a missing optional bundle produces when the class is first linked.
Spreading a jgit type into a signature `CompareTool` touches would turn the missing bundle into a failure of the whole tool.

**Deleting the platform log underneath the framework is safe here, and it was checked rather than assumed.**
Equinox reopens the log file per write, so `eclipse_clear_log` deleting it leaves logging working and the file reappears on the next entry.
The tool still writes an entry and reads it back on every real clear, and reports `stillLogging`, because the failure mode if that ever changes is silent: entries would go to an unlinked file and only surface as an empty log much later.
`LogStateToolsTest.clearingLeavesTheLogWritableAndReadable` is what turns this from a belief into a check.

**"The most recent" is a global answer, and more than one client makes it a wrong one.**
The build, test run, sampling and provisioning registries are per IDE, not per client, so the latest entry may belong to somebody else while looking exactly like a correct answer.
Those four tools consult `ClientSessions` and refuse the implicit default when a second client is connected, naming the ids instead.
Sessions are counted in `ActiveSessions` from the `Mcp-Session-Id` header, because the SDK transport does not expose its own, and a session is dropped on the terminating DELETE rather than only by ageing out: without that, a client reconnecting after `eclipse_restart` counts as two for the length of the window and the defaults refuse for no reason.
`ClientSessions` lives in core with the provider injected by the server bundle, since core cannot depend on it.
With no provider the answer is one, which keeps the defaults working headless and in tests.

**A timestamp from the caller is not a point in the IDE's log.**
`eclipse_mark_log` records a byte position, and `eclipse_get_log_entries` takes it as `marker`.
The `since` filter needs the caller's clock compared against timestamps the IDE wrote, which is the same shape as the UTC-versus-local mistake that cost a wrong conclusion about a published build.
It also cannot see a rotation: a `since` window spanning one silently loses entries, while a marker whose file has shrunk reports `markerStale` instead of returning a window that would be wrong.

**One file on disk is many workspace paths, and `eclipse_search_text` has to collapse them.**
754 of 755 projects in a platform workspace are nested inside another project, so the same file arrives once per path and a raw count is inflated by an unpredictable factor.
Matches are keyed by `getLocationURI()` plus offset, the extra paths become `alsoVisibleAs`, and `duplicatePathsCollapsed` reports the fold.
Derived-resource exclusion does not help here, because none of the duplicates is build output.
This matters more for this tool than the others: telling the paths apart needs a filesystem, which is exactly what the tool exists to remove the need for.

**`eclipse_delete` is a resource delete, and PDE's participants do not fire on it.**
JDT's delete refactoring, which is what `ManifestTypeDeleteParticipant` is written for, has no usable public API: `DeleteDescriptor` carries no setters and the processor is x-friends to `org.eclipse.jdt.ui`.
So the tool uses LTK's `DeleteResourcesDescriptor`, which passes `IResource`, while the participants enable on `IType` and `IPackageFragment`.
`plugin.xml` and `Export-Package` are therefore not updated, the description says so, and the answer reports the evidence that will dangle.
Do not "fix" this by driving the internal processor without deciding to own that dependency.

**`eclipse_clean_up` takes a discouraged dependency on purpose, and it is the only one here.**
`CleanUpConstants` and the `*CleanUpCore` classes are x-friends to `org.eclipse.jdt.ui`, and the friends list does not include JDT-LS despite JDT-LS using them, so "JDT-LS does it" is evidence that it works rather than that it is supported.
That dependency was a deliberate decision, not an oversight: there is no public alternative, reimplementing JDT's transformations is not an option, and the value is a whole class of tooling.
It compiles with a discouraged-access warning and can break in any JDT release with no compile-time signal. `CleanUpRefactoring` was rejected as the entry point because it lives in `org.eclipse.jdt.ui` and would drag the UI in.
`RemoveUnusedImportsTool` stays as it is, on public API, since a targeted tool with no such dependency is worth keeping even though the clean-up can do the same job.

**JDT's clean-up options are a tree, and enabling only the leaf does nothing.**
`USE_LAMBDA` without `CONVERT_FUNCTIONAL_INTERFACES`, or `VARIABLE_DECLARATIONS_USE_FINAL` without its three sub-options, runs the clean-up and produces no edits, which is indistinguishable from the pattern not applying.
`CleanUpEntry.companions` declares those per clean-up so a caller cannot hit it.

**A clean-up that touches imports needs the jdt.ui preference node seeded.**
The lambda clean-up died with `"order" is null` headless, which is the same NPE `OrganizeImportsTool` was already working around; `ensureCodeStylePreferences` is shared rather than duplicated.

**PDE's project model cannot round-trip a real manifest, so `eclipse_edit_manifest` refuses what it cannot rewrite.**
`IPackageExportDescription` carries a name, a version, `friends` and an api flag, and nothing else; `apply()` rewrites the WHOLE header from the model.
Shipped without a guard it dropped every `ui.workbench=split;mandatory:="ui.workbench"` attribute from `org.eclipse.ui.workbench`, which is what makes that bundle resolve at all, and re-sorted the `x-friends` lists because `friends()` is a `SortedSet`.
`unsupported` parses the existing headers with `ManifestElement.parseHeader` and refuses when any attribute or directive outside `version`, `x-internal`, `x-friends`, `bundle-version`, `visibility` and `resolution` is present.
That includes `uses:`, so most platform bundles are refused. Refusing is correct: this tool cannot safely edit a manifest it cannot faithfully rewrite, and the failure is silent corruption rather than an error.

**Scoping an update to "where this unit came from" cannot find an update.**
`getInstallableUnitSources` answers where the INSTALLED version lives, and a new version is published somewhere else by definition.
With a composite whose child location changes per release, the child the current version came from is exactly the one that will never hold a newer one, so a scoped check reported "no updates" while the composite had one.
`widenToAllRepositories` retries against every enabled repository when the scoped resolution finds nothing, and the answer says it did.
Do not remove the scoped attempt; it is still the cheap path when the unit has not moved. Do not trust it alone either.

**Two spellings of one key is how a lookup silently matches nothing.**
`FindReferencesTool.declarationsOf` built `path + "@" + offset` while `locationOf` built `path + ':' + offset`, so the declaration flag was computed, reported and never once true.
The build was green and the field simply never appeared. Both now go through one `locationOf(IResource, int)`, and `marksTheDeclarationAmongWriteAccesses` covers it.

**An e4 application model is a registry position, and leaving it out deleted live code.**
A class named by a `bundleclass://<bundle>/<fqn>` URI in a `.e4xmi` is instantiated by the workbench at every start and referenced from no Java at all.
`eclipse_list_declarations` reported three such addons as dead with zero references and no evidence, and `eclipse_delete` removed them, after which everything still compiled.
`indexApplicationModel` walks the project for `.e4xmi` files, because unlike `plugin.xml` they live wherever the bundle put them, and records a `bundleclass` URI as evidence of kind `e4xmi`.
"Documented as not covered" was not good enough for a tool that deletes: a gap in a report is a caveat, the same gap in a delete is data loss.

**Require-Bundle dependents are not consumers of a package.**
The first version of the export-removal guard counted every bundle that requires the exporter, which on a platform bundle is dozens and refused almost every removal.
`importedBy` is exact and blocks; `mightAlsoUseIt` lists the Require-Bundle dependents, says they may or may not use the package, and does not block.

**A self update runs inside the bundles it is replacing.**
Every bundle here is in the same feature, so `eclipse_update` on that feature stops both the bundle serving the request and the bundle running the provisioning job.
It happened: one log line, `MCP server stopped`, no install, no rollback, and an IDE left with no server and no way to reach it.
`UpdateTool.selfUpdates` refuses unless `acknowledgeSelfUpdate` is passed, and `EndpointFile.markStopped` leaves a record instead of deleting the file, so a client has something to read afterwards.
Neither of those makes a self update safe, they make it declared and legible. The real fix is to stop driving the operation from inside the feature, and it is not written yet.
Do not treat the dropped connection as the bug; a client can wait that out. The bug is the bundle that never comes back.

**Every long running IDE operation a client drives has a dialog in it somewhere.**
Four so far: p2's unsigned content prompt, the workspace chooser on restart, the launch time "Errors in Workspace" prompt raised by the `org.eclipse.debug.ui` status handler, and the compare framework's "no differences" message when a `CompareEditorInput` has a null result.
Each blocked a call nobody was watching, and each was invisible in the protocol until someone looked at the screen.
The compare one is the cheapest to avoid and the easiest to walk into: `CompareTool` always returns a `DiffNode`, so the framework never has an empty result to complain about, and the answer carries `identical` instead.
The answer is always the same: do not let the dialog be raised on a path a client drives, answer it, and report what was answered.
Before adding a tool that runs project code or touches the installation, look for a prompting `IStatusHandler` on that path rather than waiting for it to surface.

**p2 prompts block a provisioning job, and a blocked job looks like a slow download.**
The IDE's `UIServices` raises a modal dialog for unsigned content, and from a client that is indistinguishable from a slow mirror until the call times out.
`HeadlessTrust` replaces it for the duration of a provisioning call: it answers rather than prompting, and records what it was asked about so the result says why an install went through or did not.
`trustUnsigned` defaults to true, and that was a reversal.
It defaulted to false on the argument that the repository allowlist bounds where code comes from and trusting unsigned artifacts removes the remaining check on what that code is.
Two things broke that argument.
`eclipse_add_repository` lets a client configure a new site, so the allowlist never was the bound it was taken for: whoever can call these tools already decides what gets installed.
And a locally built p2 repository is unsigned by definition, so the default made the self-update workflow these tools exist for impossible however the caller asked, which is worse than the risk it was refusing.
What must stay is the part that makes an accepted install auditable rather than silent: `persistTrust` false so nothing reaches the IDE's permanent trust store, `trustAlways` never returned because p2 writes it into a preference and a switch flipped once is never flipped back, and every prompt recorded.
Do not "simplify" `HeadlessTrust` by returning `trustAlways`, and do not stop recording.

**The trust prompt list is capped, and the count is the honest number.**
An SDK install asks about hundreds of artifacts, so `prompts` stops at `MAX_PROMPTS` while `promptCount` keeps counting, and the answer carries `trustPrompts`, `trustPromptsTotal` and `trustPromptsTruncated`.
The list is evidence of what kind of content was accepted, not a manifest of it.
The result used to also repeat the same array under `trustedContent` or `refusedTrust` depending on the outcome; naming a key after the intention hid what p2 had actually asked about when the operation then failed on trust, so there is one key whatever happened and `trustedUnsigned` carries the verdict.

**The provisioning tools update the IDE that is running them.**
If a bad build lands, the tools that would fix it are the tools that just broke.
`eclipse_restart` therefore lives in the ui bundle and does not depend on the p2 bundle, so a half applied update can still be recovered, and every provisioning result carries the previous configuration timestamp so a human can revert from Installation History without the server.
`eclipse_install` refuses repositories the IDE is not already configured with: adding one fetches and runs code from a new source, which is the user's decision and not the server's.
Do not replace that allowlist with a single opt-in preference; a switch flipped once is never flipped back.
It is friction on the way to a new source and not a security boundary, because `eclipse_add_repository` configures one through this same server; read it that way and do not build another guard on top of it, which is the mistake `trustUnsigned` made.

**Read what the answer needs off a widget before the action that disposes it.**
`eclipse_dismiss_dialog` built its success message as `"pressed " + label(target)` after `notifyListeners` had already run, and the button that closes a JFace dialog disposes itself along with the shell.
Every press that actually worked therefore came back as `SWTException: Widget is disposed`, and the dialog was gone by the time the error arrived: a completed operation reported as a failed call, which is the one thing an answer must never do.
The press was never the problem, so nothing in the tool looked wrong; the reporting was.
This generalises past dialogs. Any tool whose action can dispose what it is about reads its labels, titles and bounds first, and reports disposal as a fact of its own, the way `shellClosed` now does, rather than discovering it by throwing.

**`SearchMatch.getResource()` lies about where a binary match lives.**
For a match inside a jar it returns the project that owns the classpath entry, so the path is a bare project name with no file component and the project attribution is affirmatively wrong.
`JavaModelSupport.describeLocation` is the only place that should turn a match into path/project/library: it reports `origin` and puts the jar in `library` with path and project null.
Never write `match.getResource().getFullPath()` into a result again.

**Unscoped type resolution finds build output before source.**
`IJavaProject.findType` happily returns a class file from a product jar under `target/`, whose `getCompilationUnit()` is null.
`JavaModelSupport.findType` now prefers a source type and only falls back to a binary one, and `RenameTool` refuses a binary element outright, because `RenameTypeProcessor.checkInitialConditions` dereferences the compilation unit without checking and dies on a raw `NullPointerException`.

**The sampler's budget counts ticks, not stacks.**
Counting stacks meant that on an IDE with seventy live threads a budget of 200 was spent after three rounds, roughly 300 ms, so sampling stopped before the operation being profiled had started.
Parked and waiting threads are also excluded by default, otherwise `Unsafe.park` is reported as the hot frame of an idle IDE.

**Root capture returns a blank image on this machine, and on GTK4, without erroring.**
`GC.java` only calls `gdk_cairo_set_source_window` when `!GTK.GTK4`, so on GTK4 the group is painted empty and handed back as a valid image.
Separately, a compositing window manager redirects a window's contents into an offscreen pixmap, so reading the X11 root drawable through XWayland also yields uniform pixels.
The uniform check is therefore the only reliable signal, and `Control.print` is the fallback when it trips. It has GTK gaps, which is why it is second rather than first, but the alternative in that case is no image.
Do not add an environment check back to `unsupportedReason`: `WAYLAND_DISPLAY` and `XDG_SESSION_TYPE` stay set when `GDK_BACKEND=x11` binds X11 through XWayland, so the environment cannot distinguish a display that captures from one that does not. It was tried and it refused on a machine where `widgetPrint` works.

**Never interpolate a Java element into a message with `toString()`.**
`IPackageFragmentRoot.toString()` prints every package it contains, which turned one rename refusal into 221 lines with the useful advice at the bottom.
Use `getElementName()`, `getFullyQualifiedName()` or `JavaModelSupport.describe`.

**The sampler must not need the UI thread or a workspace lock.**
It exists to diagnose freezes, so anything that queues behind one is useless.
`ThreadMXBean` does not require the sampled thread to be responsive; do not replace it with anything that runs on the Display.
Note the wider caveat: tools that avoid the UI thread can still block on the workspace or Java model lock if the frozen UI thread holds one, so "MCP still answers while the IDE is frozen" is reliably true for thread contention and only usually true for lock contention.

**The feature lists third party bundles the Eclipse SDK does not ship.**
Jetty ee11, the MCP SDK, Jackson 3, networknt and reactor are included so that the p2 repository is installable.
`slf4j.api` and `jakarta.servlet-api` are deliberately left out, because the host IDE ships satisfying versions.

**`com.vogella.eclipse.mcp.ui` is the branding plugin of the feature.**
`feature.xml` names it in its `plugin` attribute, and its `about.ini` and `about.properties` are what *Help > About Eclipse IDE > Installation Details > Features* shows.
`featureImage` has to be 32x32; the larger source of that icon is `icons/eclipse-mcp-server.png` in the repository root.
No `about.mappings`, because its `{0}` build id token is substituted by PDE build and not by Tycho, so it would show up literally.

**Versions in `META-INF/MANIFEST.MF` and the Jetty imports are pinned to `[12.1.12,13)`.**
`jetty-ee11-servlet` requires the Jetty core packages at that exact floor, and the IDE ships an older 12.1.x that would not satisfy it.

**Capturing on a HiDPI monitor: `print` paints at the device scale.**
Drawing into an image sized in points wrote a 2x picture into a 1x canvas and kept the top left quarter, silently.
`ScreenshotTools` prints through an `ImageGcDrawer` and reads `getImageData(zoom)`.
Correcting with a GC transform instead was tried and shipped and is wrong: it shrinks the paint into a quarter of a canvas that is still device sized.
`capturedArea` is the pixels, `areaInPoints` the widget, `zoom` the ratio, and `scaleMismatch` fires when the three disagree, which is the check that would have caught the transform.
Unpainted canvas is magenta, never white, because white is what the unstyled widgets of a broken dark theme look like.

**A computed CSS value is read back off the widget, so it is not evidence that a rule ran.**
`CSSEngine.retrieveCSSProperty` asks the property handler, and the SWT handlers answer from the widget's current colour or font, which means a `ToolBar` no rule matches reports the window system's grey exactly the way a themed one reports the theme's grey.
`CssStyling.styles` therefore also reads the cascade, `getViewCSS().getComputedStyle(element, pseudo)`, and reports `declared` and `origin` beside `computed`.
That is the merged declaration of the rules that matched, which is as far as the engine goes: it keeps no list of which rule or which stylesheet a property came from.

**The CSS engine's API is not binary stable, and this bundle is compiled against one release and run on another.**
`CSSEngine.parseStyleSheet` changed its return type from `org.w3c.dom.stylesheets.StyleSheet` to `CSSStyleSheetImpl`, which is a source-compatible change and a binary incompatible one: a call site compiled against the target platform dies with `NoSuchMethodError` on an IDE that has the newer engine.
`CssStyling.parse` calls it reflectively for that reason, and `CssStyling.rules` reads the rule count under either spelling.
The development IDE here already runs a newer platform than `com.vogella.eclipse.mcp.target` names, so this is the normal case and not an exotic one.
Check a signature against both before adding a call: `javap` on the jar in `~/.m2/repository/.cache/tycho` answers it in a second.

**`eclipse_apply_css` reaches the theme engine reflectively, because the two methods it needs are not on the interface.**
`resetCurrentTheme()` and `getCSSEngines()` live on `org.eclipse.e4.ui.css.swt.internal.theme.ThemeEngine`; PDE's CSS scratch pad casts to it and carries a `FIXME` asking for them to be exposed.
The engine itself is looked up by name through `IEclipseContext`, so nothing here compiles against `IThemeEngine`, whose package is `x-friends` to a list this bundle is not on.
Re-applying the theme first is what makes a snippet replace the one before it instead of stacking, and it is the whole of what `reset` does.

**`org.w3c.dom.css` comes from the JRE and is deliberately not imported.**
The package lives in the `jdk.xml.dom` module, which Equinox exports as a system package because `eclipse.ini` passes `--add-modules=ALL-SYSTEM`.
`org.eclipse.e4.ui.css.core` uses it with no `Import-Package` either.
Adding an optional import here was considered and rejected: an optional import that the target platform cannot resolve turns the package into a compile error rather than a graceful degradation.

**A `Location`'s URL is not a path, and the two obvious ways of making one are both wrong off Linux.**
`FileLocations` is the only place that turns one into a `java.nio.file.Path`.
`url.getPath()` yields `/C:/eclipse` on Windows, which the path parser rejects outright, and it leaves `%20` in place wherever the URL was encoded, so an installation under `Program Files` misses on every window system.
`Path.of(url.toURI())` gets both right and throws on the other half of the problem, which is that Equinox does not always encode what it puts in a Location URL, so a path with a space in it is not a URI at all.
The helper tries the URI form and decodes the raw one by hand when that fails.
This was a whole class of bug rather than one: the configuration area, the install location, the workspace a restart is sent to, and the jars a target platform search reads were each written a different way, and three of the four were unusable on Windows.

**This repository is developed and tested on Linux, so anything platform shaped is a guess until somebody runs it elsewhere.**
The fixes so far were found by reading rather than by failing, which means the review is the record: shell selection and process output encoding in `eclipse_run_command`, the URL-to-path conversions above, CRLF line endings in `eclipse_edit_file`, a Windows drive letter read as a URI scheme in `eclipse_apply_css`, an unquoted flight recording path with a space in it, and the token file's access rights, which POSIX permissions cannot express on Windows and an ACL can.
Prefer `FileLocations.isWindows()` over a fresh `os.name` test, and `Path.startsWith` over a string prefix, since path comparison is case insensitive on Windows and a text one is not.

## Verifying UI work

A UI change is not verified by reading the JSON.
Capture the image and look at it, and crop to bounds from `eclipse_get_widget_tree` rather than by eye.
Three release cycles went into a fix whose premise was a crop of the wrong widgets: buttons that looked like one stack's toolbar belonged to another.
The cross-check that settles which widget you are looking at is cheap and belongs before the first attempt, not after the second.

## Releasing

Run `gh workflow run release.yml`.
`.github/workflows/release.yml` builds `main`, copies the p2 repository into `releases/<built version>/` on the `gh-pages` branch, regenerates the composite metadata with `releng/update-composite-site.sh` and pushes the site.
It takes no version input: the directory is named after the feature jar the build produced, qualifier included, so publishing twice from the same source still lands on two different URLs.

The version in the manifests is meant to stay put across ordinary changes.
Tycho stamps every build with a fresh `yyyyMMddHHmm` qualifier, and p2 treats a higher qualifier under an unchanged version as an update, so *Check for Updates* picks up a new build without any version being bumped.
Bump `Bundle-Version`, `feature.xml` and `pom.xml` together when something worth naming lands, not per change.

Pushing a `v<version>` tag runs the same workflow and additionally creates the GitHub release with the repository archive attached.
That is the only thing that produces a downloadable zip, so a version anyone else should be able to install deserves a tag.

The published site is a p2 composite repository at `https://vogellacompany.github.io/eclipse-mcp-server/`.
The workflow passes `--only "$version"`, so publishing deletes every other `releases/<version>/` and the composite is left with a single child.
It is `--only` and not `--keep 1` because "newest" under `sort -V` is not "the one just published": `0.2.1` sorts above `0.2.0.202608201136`, so `--keep 1` once deleted the build it had just published and kept a stale directory instead.
Older builds stay reachable only as the repository zip attached to a tagged GitHub release.
Never edit `compositeContent.xml`, `compositeArtifacts.xml`, `p2.index` or `index.html` on `gh-pages` by hand; they are generated.
Never overwrite an existing `releases/<version>/` with different content, because p2 caches repositories aggressively and a changed repository under an unchanged URL produces confusing install failures.

Deleting a release directory does not shrink the branch: `gh-pages` keeps every published copy in its history, at roughly 9 MB each.
Only a force-pushed orphan commit would reclaim that, and it would throw away the history of the site.

Pushing anything under `.github/workflows/` needs a token with the `workflow` scope, or an SSH remote.

The release replaces the composite child rather than adding one, so a client that already knows the site has a cached artifact repository pointing at a directory that is now gone.
Refreshing metadata alone then resolves the new version and fails in the download phase with "No repository found containing", naming neither the cache nor the site.
`Provisioning.refreshArtifacts` refreshes both sides for this reason; it cost two failed self updates to find.

`eclipse_restart` refuses while a modal dialog is open in the IDE, and self updating through the running server means a human has to be reachable to close one.

When verifying a freshly published release against the live site, remember that Tycho caches remote p2 repositories for 60 minutes under `~/.m2/repository/.cache/tycho`.
A resolve that returns the previous version right after a release is almost always that cache, not a broken publish.
`-Dtycho.p2.transport.min-cache-minutes=0` is not enough; delete the cache directory for the host.

## Conventions

Java 25, tabs for indentation, the Eclipse formatter defaults.
Javadoc says what a class or method does in a sentence or two; no `@param` or `@return` for anything obvious from the name.
Inline comments are rare and explain the non-obvious why, never the what.
Markdown uses one sentence per line.
