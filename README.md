<img src="docs/images/logo256.png" alt="" width="128" align="right">

# Eclipse MCP Server

Turns a running Eclipse IDE into an [MCP](https://modelcontextprotocol.io) server, so that external LLM clients (Claude Code, Cursor, any MCP-capable agent) can ask the IDE for information they cannot cheaply reconstruct from files alone.

An agent with a shell already has files, grep and git.
What it does not have is the resolved Java model, the incremental builder's problem markers and the user's current editor context.
Those are the capabilities exposed here.

Built and maintained by [vogella GmbH](https://vogella.com/services/), and used in our consulting and in our AI based Java cleanup services [for legacy code and code optimization](https://vogella.com/services/).

Most tools are read-only. The exceptions are marked as such below: `eclipse_organize_imports` and `eclipse_format` rewrite the file they are pointed at, `eclipse_build` runs the project's builders, `eclipse_set_preference` changes IDE configuration, `eclipse_set_project_state` opens and closes projects, `eclipse_set_bree` rewrites plug-in manifests, `eclipse_add_repository` and `eclipse_remove_repository` change the configured update sites, `eclipse_run_command` runs arbitrary commands in directories that same preference page has to name first, `eclipse_run_workbench_command` runs whatever workbench command it is given, `eclipse_manage_window` and `eclipse_log_status` open and close windows and write log entries, and `eclipse_exit` shuts the IDE down.
The debugger tools change things too, in narrower ways that each description states: `eclipse_set_breakpoint` edits the breakpoint list, `eclipse_debug_launch` starts a process under the debugger, `eclipse_debug_evaluate` runs an expression inside the debugged program, and `eclipse_debug_control` steps and terminates it.
There is no general file writing and no refactoring.
Commands run only in directories the user has named, and the git operations are a branch switch and a pull request fetch, both through EGit.
The server is **disabled by default**, listens on the loopback interface only, and rejects every request that does not carry a bearer token.

<p align="center">
  <img src="docs/images/splash-animated.webp" alt="The Eclipse MCP Server splash screen" width="600">
</p>

Optionally the IDE can come up under this splash instead of the platform's own.
It is **off by default**; set `replaceSplash` in the installation's configuration scope to turn it on.
The launcher paints the splash before OSGi starts, so the change takes effect at the restart after the one that applies it.

## Building

Requires JDK 25 and Maven 3.9 or newer.

```bash
mvn clean verify
```

The build resolves everything from the target definition in `target-platform/com.vogella.eclipse.mcp.target`, so no extra setup is needed.
It produces a p2 repository under `update-site/com.vogella.eclipse.mcp.repository/target/repository`.

## Installing

In Eclipse, choose *Help > Install New Software*, add

```
https://vogellacompany.github.io/eclipse-mcp-server/
```

as an update site and install the **Eclipse MCP Server** feature.
That URL is a composite repository which always offers the newest version.
The site carries that release alone; publishing a new one removes the previous directory under `releases/`, so there is no older version to point at.

To install or to pin an older version, use the repository zip attached to its GitHub release, through *Add > Archive*.
When building from source, the same repository is produced under `update-site/com.vogella.eclipse.mcp.repository/target/repository` and can be added as a local site.

## Releasing

`gh workflow run release.yml` builds `main` and publishes it to the update site, under a directory named after the qualifier of the build.
The site keeps that build alone; the previous one is deleted.

Pushing a `v<version>` tag runs the same workflow and additionally creates the GitHub release with the repository archive attached, which is the only way a downloadable zip is produced.

## Developing in the IDE

1. Import the projects with *File > Import > Existing Projects into Workspace*, pointing at the repository root and enabling *Search for nested projects*.
2. Open `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` and click *Set as Active Target Platform*. Resolving it downloads the Eclipse SDK, so the first run takes a while.

## Enabling the server

*Preferences > General > MCP Server*:

* **Enable MCP server**, off by default
* **Port**, `8642` by default
* **Tool call timeout**, `30` seconds by default, between 5 and 3600

The setting takes effect immediately, and the server also starts on the next IDE startup while it stays enabled.

The timeout bounds a single tool call. It is read per call, so raising it does not need a restart.
Raise it when a workspace is large enough that a refreshing `eclipse_get_problems` or a build does not finish in 30 seconds.

The same preference page shows the endpoint once the server is listening: the URL, the bearer token and the path of the discovery file, each with a *Copy* button, plus a *Regenerate token* button.
When the port is already in use the server does not fall back to another one, it stays down and the page says why, so the URL never changes behind a client's back.
That it is listening, and on which port, is also written to the Error Log view.

## Connecting a client

On startup the server writes a discovery file so that no value has to be copied by hand:

```
<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json
```

```json
{
  "state": "listening",
  "url": "http://127.0.0.1:8642/mcp",
  "token": "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b",
  "workspace": "/home/me/workspace/swt",
  "startedAt": 1787300000000
}
```

`state` is `listening` or `stopped`. The file is left behind with a `stopped` record rather than deleted, because a missing file cannot be told from one that was never written, and the case that matters most is the one where the server does not come back.

**Several clients can use one server.** The transport is session based, so each client gets its own session over the same port, and the bearer token is the same for all of them: the server cannot tell them apart, and they all act on one workspace with no locking between them.

The trap is not the connection, it is *the most recent*. `eclipse_get_build_status`, `eclipse_get_test_results`, `eclipse_stop_sampling` and `eclipse_get_provisioning_status` answer about the latest entry in a **global** registry when the id is omitted, so another client's run started in between would be reported as yours and would look entirely correct. While more than one client is connected those four **refuse the implicit default** and name the ids to choose from. Pass `buildId`, `runId`, `sessionId` or `operationId` explicitly and it never arises.

A client is counted from the session id on its requests and drops out when it ends its session or after a minute of silence, so reconnecting, which is what a client does after `eclipse_restart`, does not make it look like two.

`startedAt` identifies the server process. It matters after `eclipse_restart`, which answers *before* it restarts: the old server keeps responding for a couple of seconds, so a plain reachability check succeeds against the process that is about to die. Compare `startedAt` across the reconnect to know the new one is really up.

The file is created with owner-only permissions and deleted when the server stops.

The token is generated on first use and kept in user scope, `~/.eclipse/com.vogella.eclipse.mcp.server/token`, with owner-only permissions.
User scope rather than the workspace is deliberate: the port is one preference with the same default everywhere, so a workspace scoped token would give every workspace its own secret behind one address, and a client registered against one workspace would be rejected by the next.
It survives IDE restarts, p2 updates and switching workspaces, so a client has to be configured only once.
A workspace that already carried a token from an earlier version keeps it, so upgrading does not orphan a client that is already registered; the old file is renamed to `token.migrated`, because one still called `token` beside the live `endpoint.json` is a trap for whoever reads it while diagnosing.
It is written through a temporary file and an atomic move: truncating in place leaves a window in which there is no token, and a second IDE starting inside that window mints a new one and invalidates every client of the first.
It is written as a plain file rather than as a preference because a preference file is world readable and this is a secret.
Because user scope is shared by every Eclipse this user starts, a second instance regenerating the token replaces the one the first is serving; the build sets `-Dcom.vogella.eclipse.mcp.tokenDirectory` so that running the tests cannot do that to a real IDE.
*Regenerate token* on the preference page replaces it and restarts the server, which rejects every client still using the old one.

The transport is Streamable HTTP.
Every request has to carry the token:

```
Authorization: Bearer <token>
```

Requests without it are answered with `401`. The message says the token is stale rather than that something needs re-authorizing, and names both files it can be re-read from; the `WWW-Authenticate` challenge repeats it as `error="invalid_token"` with an `error_description`, the way RFC 6750 defines.

Whether any of that reaches the caller is the client's decision, and Claude Code's is unhelpful: it renders every `401` as "requires re-authorization" and discards both the body and the challenge, so a stale token arrives as an authorization problem that no amount of authorizing fixes. Answering `200` with a JSON-RPC error would get the text through, and this server does not do it: a rejected credential has to be a `401`, and trading every other client's auth handling for one client's rendering is the wrong trade. The mitigation that works is the token not moving in the first place.

**Which workspace answered.**
The port is the same everywhere, so with two IDEs open the one that started first owns it and the second stays down.
`workspace` in the discovery file, and in the `401` message, names the workspace a server is serving, which is the only way to tell which IDE a client actually reached.
An MCP client usually turns a rejected token into an empty tool list rather than an error, so a misconfigured client looks like an absent server; the `401` message is written to be readable when it does surface.
This is a plain bearer token, not the MCP authorization specification, so there is no OAuth flow and nothing to discover: a client that can send a static header is enough.

The socket is bound to `127.0.0.1`, so it is not reachable from another machine.
The `Host` header is additionally checked against `127.0.0.1` and `localhost`, which keeps a browser on the same machine from reaching the server through a rebound DNS name.

For Claude Code:

```bash
claude mcp add --transport http eclipse http://127.0.0.1:8642/mcp \
  --header "Authorization: Bearer $(jq -r .token <workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json)"
```

## MCP capabilities

The server offers tools and nothing else.
It declares the `tools` capability with `listChanged: false`, which means it answers exactly these methods:

| Method | Notes |
|---|---|
| `initialize` | reports the server as `eclipse-mcp` with the bundle version, plus an instructions string |
| `ping` | |
| `tools/list` | the tools below |
| `tools/call` | arguments are validated against the tool's input schema before the tool runs |
| `notifications/initialized`, `notifications/roots/list_changed` | accepted and ignored |

Everything else, `resources/list`, `prompts/list`, `logging/setLevel` and `completion/complete` among them, is answered with method not found.
Sessions are carried in the `mcp-session-id` header, a `GET` opens the server-to-client SSE stream and a `DELETE` ends the session.

## Scripting the IDE from outside

The server is a plain HTTP JSON-RPC endpoint, so anything that can POST JSON can drive the IDE; `eclipse_run_script` turns a sequence into one call with assertions, and two scripts in `releng` turn that into something a build can run.

```bash
# against the IDE you are working in
releng/mcp-script.py releng/scripts/content-assist.json

# against a throwaway IDE, on a workspace and a port of its own
releng/mcp-test-ide.sh --ide /path/to/eclipse --junit results.xml releng/scripts/smoke.json
```

`mcp-script.py` finds the endpoint and the bearer token the way any client does, runs the file, prints each step with its timing and the expectations that failed, exits non-zero when any did, and writes JUnit XML so a CI server shows the steps as test cases.
It is deliberately thin: the expectations are evaluated in the server, so an LLM client asking for the same script gets the same verdict.

`mcp-test-ide.sh` starts the IDE on a temporary workspace with the server preference written **before** the first start, which is the only way out of a circle: whether the server runs is an instance preference, so a workspace nobody has switched it on in comes up with nothing listening and no way left to ask why.
It waits for the discovery file, runs the scripts, and takes the IDE down again, so a working IDE is never touched.

**The installation is reused, not copied, and the test IDE is still pristine.**
Only the workspace and the configuration area are fresh, 2.2 MB against a 553 MB install.
That works because of how the two are split: `bundles.info` lives in the configuration area, while the paths inside it are relative to the installation, so a configuration of its own shares all 429 MB of `plugins/` and still decides for itself which jars to load.
Any line pointing at a substituted jar is rewritten back to the shipped one before the IDE starts, so a test never silently measures a bundle somebody else patched into the installation being worked in.
`--shared-config` turns that off and runs whatever the installation is currently configured with.

A p2 bundle pool would achieve the same sharing for genuinely separate installations, and is the right answer when the test IDE has to install or uninstall features of its own; for running scripts against the shipped bundles the private configuration area is smaller and needs no pool to be set up.

**Scripts have to be deterministic to be worth running.** The smoke script waits with `eclipse_wait_until_quiet` before capturing a view it has just opened, because the first version captured 28 ms after opening it and passed or failed depending on the machine.

## Tools

Every tool returns a single text block containing pretty-printed JSON.
Every list-returning tool honours `maxResults` and reports `total` and `truncated`, so the model can tell when it is seeing a partial answer.
Read-only except the tools marked as changing something: `eclipse_organize_imports` and `eclipse_format` rewrite a file, `eclipse_build` runs builders, `eclipse_set_preference` writes configuration, `eclipse_set_project_state` opens and closes projects, `eclipse_write_file` creates and replaces files, `eclipse_set_target_platform` replaces what every plug-in project compiles against, `eclipse_set_theme` switches the theme the whole IDE is styled by, remembered across restarts by default, `eclipse_install_bundle` replaces a bundle in the running framework, and `eclipse_hot_code_replace` swaps the bytecode of classes inside the running JVM.

### `eclipse_list_projects`

Lists the projects in the workspace, with their natures and open/closed state.
Takes `maxResults` (500), and reports `total` and `truncated`.

```json
{"projects":[{"name":"com.example.app","open":true,
              "natures":["org.eclipse.jdt.core.javanature"],"natureSource":"model",
              "location":"/home/user/git/app"}]}
```

A closed project still reports its natures, read from `.project` on disk, and `natureSource` says which of the two answered: `model`, `projectFile`, or `unknown` when the file could not be read.
`IProject.getDescription` fails on a closed project, and reporting that as "no natures" is worse than saying nothing.
A client classifying projects by nature otherwise gets a different answer for the same workspace depending on which projects happen to be open at the time, which is not a rule but a coin flip.

### `eclipse_resolve_path`

Answers where something is.
Given project names, workspace paths or absolute paths in any mix, it reports for each the project, the workspace path, the location on disk, the repository root and the path inside that repository. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `of` | array of string, required | | Project names, workspace paths or absolute paths, in any mix. |

This is the mapping a command line cannot work out and the IDE already holds, and it is what makes git usable from outside.
`org.eclipse.compare` becomes a repository root to run in and a path to pass after `--`, so `git log`, `blame` and `log -L` can be composed directly instead of being guessed at.

It resolves in both directions, so an absolute path coming back from a build or a stack trace turns into the project it belongs to.

Nothing here needs EGit.
The repository is found by walking up for `.git`, which also resolves a linked worktree.

### `eclipse_get_problems`

Returns the compilation errors and warnings computed by the incremental builder.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `severity` | `error` \| `warning` \| `info` \| `all` | `error` | Only problems of exactly this severity. `all` returns every severity. |
| `project` | string | all projects | Restrict to this project name. |
| `pathPrefix` | string | all paths | Restrict to workspace paths starting with this prefix. |
| `maxResults` | integer, 1 to 2000 | 200 | |
| `refresh` | boolean | `true` | Refresh from disk and wait for the build before reading the markers. |

Errors sort before warnings before infos, so truncation keeps the most important entries.

```json
{"total":3,"truncated":false,"upToDate":true,"autoBuild":true,"problems":[
  {"path":"/app/src/com/example/Main.java","project":"app","line":42,
   "severity":"error","message":"Foo cannot be resolved to a type",
   "type":"org.eclipse.jdt.core.problem"}]}
```

A client that edits files through its own shell is invisible to the IDE until the workspace is refreshed, so without `refresh` the markers describe the state before those edits.
That is why it defaults to `true`.
`upToDate` says whether the refresh and the build actually completed, and `autoBuild` reports whether the workspace builds on its own; when `upToDate` is `false` the problems may be stale.
Set `refresh` to `false` for a faster answer when nothing has changed on disk.

### `eclipse_mark_problems`

Records the problems the workspace has right now and returns a marker. Changes nothing, no arguments.

Pass it to `eclipse_get_problems` as `marker` and the answer is only what appeared since, plus `resolved`, the ones that went away. Everything unchanged is omitted, which is the point: the alternative is reading every problem before and after and diffing them client-side, which on a large project is a hundred kilobytes a call for an answer that is usually a few lines.

The diff is taken over the scope of the `eclipse_get_problems` call, not over the whole workspace the marker recorded. A marker is workspace wide and a query usually is not, so without that narrowing a project-scoped call reports every problem in every other project as resolved while it is still sitting there. `severity`, `pathPrefix` and `messageFilter` narrow the baseline the same way. `resolved` honours `maxResults` and reports `resolvedTruncated`.

It deliberately does not build or refresh. It records the state as it stands; making that state current is the caller's decision, through `eclipse_get_problems` or `eclipse_build`, which is where the cost belongs. Only the last few markers are kept, and an aged-out one is refused by name rather than silently treated as empty.

### `eclipse_get_log_entries`

Returns entries from the platform log, the file behind the Error Log view.
This is where UI freezes reported by `org.eclipse.ui.monitoring` and exceptions thrown by builders end up.
None of those become problem markers, so `eclipse_get_problems` cannot see them.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `severity` | `error` \| `warning` \| `info` \| `all` | `all` | Only entries of exactly this severity. |
| `plugin` | string | all bundles | Restrict to this bundle symbolic name. |
| `messageFilter` | string | no filter | Only entries whose message contains this text, case insensitive. |
| `since` | string | no limit | Only entries at or after this local timestamp, `2026-08-20T11:00` or `2026-08-20`. |
| `maxResults` | integer, 1 to 500 | 50 | |
| `includeStackTraces` | boolean | `true` | Include the full stack trace of every entry and child. |
| `newestFirst` | boolean | `true` | Newest first, so that truncation keeps the most recent entries. |

The default severity is `all` rather than `error` on purpose: `org.eclipse.ui.monitoring` logs a UI freeze as a **warning**, so a default of `error` would hide exactly the entries worth asking for.

```json
{"logFile":"/home/user/workspace/.metadata/.log","total":6,"truncated":false,"entries":[
  {"plugin":"org.eclipse.ui.monitoring","severity":"warning","code":0,
   "timestamp":"2026-08-20T11:58:27.932","message":"UI freeze of 3.2s at 11:58:24.766",
   "exception":null,"stackTrace":null,
   "children":[{"plugin":"org.eclipse.ui.monitoring","severity":"info","code":0,
                "timestamp":"2026-08-20T11:58:27.932",
                "message":"Sample at 11:58:26.099 (+1.333s)\nThread 'main' tid=3 (RUNNABLE)",
                "exception":null,
                "stackTrace":"Stack Trace\n\tat org.eclipse.jdt.internal.core.JavaModelManager.create(...)"}]}]}
```

A UI freeze is a multi status whose children carry the sampled thread stacks, and those children are the whole point, so they come through in full rather than being flattened into the `UI freeze of 3.2s` headline.
Stack traces are never truncated either, which means a handful of freezes can amount to megabytes; `maxResults`, `plugin` and `includeStackTraces: false` are the levers for keeping an answer small.

The entries are read from the log file rather than from a listener registered at startup, so entries from before the server started, and from previous sessions still present in the file, are included.

### `eclipse_mark_log` and `eclipse_clear_log`

Two ways to get "everything in the log is from this run" before a long test run.

`eclipse_mark_log` records the current end of the log and returns an opaque marker. No arguments, changes nothing. `eclipse_get_log_entries` then takes `marker` and reports only what was logged after that point.

**Prefer the marker.** It is exact where `since` is not, and it destroys nothing. `since` needs a timestamp from the caller's clock checked against timestamps the IDE wrote, and those are not the same clock; the marker is a byte position in the IDE's own file, so no clock is involved. `since` also filters without shrinking, so old entries still compete for `maxResults` on a run that logs heavily. And a `since` window that spans a log rotation silently loses entries, which the caller cannot detect: a marker whose file has since shrunk comes back with `markerStale` and everything readable, rather than a window that would be wrong.

`eclipse_clear_log` **destroys the log irreversibly**, including entries from earlier sessions. It runs as a dry run unless `dryRun` is `false`, and reports `entriesDiscarded` and `bytes` either way.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `dryRun` | boolean | `true` | |
| `includeRotated` | boolean | `true` | Also remove the rotated `.log.bak` sibling. |

It deletes the file, which is exactly what the Error Log view's own delete action does. The rotated sibling goes too by default, because leaving it means a later query can still reach entries from before the clear.

**An open Error Log view is emptied with it**, and `errorLogView` says whether that happened, how many views it came to and why not when it did not.
The view parses the log once and then keeps the entries in memory, so deleting the file underneath it would leave the person at the IDE reading entries that no longer exist.
This is the same call the view's own delete action makes after it deletes the file, so the tool and the toolbar button end in the same state.
The clear itself does not depend on it: a view that refuses to empty is reported, not turned into a failed clear.

After a real clear it writes one entry, reads it back, and reports `stillLogging`. The framework writes the log through a handle of its own, so a delete underneath it could in principle leave later entries going somewhere nothing can read, and that failure would be silent until someone noticed an empty log much later. Equinox reopens the file, so this works, and `LogStateToolsTest.clearingLeavesTheLogWritableAndReadable` holds it that way rather than leaving it as an assumption.

### `eclipse_log_status`

**Writes one entry into the Error Log**, which the Error Log view shows and everything else in the IDE shares.
It exists for exercising log tooling and for marking that a run happened from the server's side, not for talking to the person at the IDE; say what you have to say in the conversation instead.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `message` | string, required | | Text of the entry. |
| `severity` | `info` \| `warning` \| `error` | `info` | |
| `pluginId` | string | this server's bundle id | Bundle symbolic name the entry is attributed to. |
| `includeStackTrace` | boolean | `false` | Attach a throwable, so the entry carries a stack trace like a real failure. |

The answer reports `verified`, whether the entry was really read back out of the log file.
Logging through `ILog` returns normally whether or not the framework can still reach its file, so arrival is proven from the consuming end, the same reasoning that put the readback into `eclipse_clear_log`.
Read what it wrote with `eclipse_get_log_entries`, and take a marker with `eclipse_mark_log` beforehand if a later read should cover only what came after.

### `eclipse_get_preferences`

Reads preferences for a qualifier and reports which scope each value comes from.
Use it to find out what has actually been customized here, auto-build being the common case: qualifier `org.eclipse.core.resources`, key `description.autobuilding`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `qualifier` | string, required | | Preference qualifier, usually a bundle symbolic name. |
| `key` | string | all keys | Exact preference key. |
| `keyPattern` | string | no filter | Glob over keys, `*` and `?`, case insensitive. |
| `scope` | `instance` \| `project` \| `configuration` \| `default` \| `all` | `instance` | Only keys set in this scope. |
| `project` | string | | Required for the project scope. |
| `includeDefaults` | boolean | `false` | Also list keys only set in the default scope. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"qualifier":"org.eclipse.jdt.core","project":null,"scope":"instance","total":1,"truncated":false,
 "preferences":[{"key":"org.eclipse.jdt.core.compiler.source","effective":"25","effectiveScope":"instance",
                 "values":{"instance":"25","default":"21"}}]}
```

`values` holds every scope that sets the key, in lookup order, and `effectiveScope` names the one that won.
An effective value without its origin cannot explain why one project behaves unlike its neighbours, which is the question this tool exists to answer.
The default value of a listed key is always reported; `includeDefaults` only controls whether keys that are *only* set in the default scope are listed at all, because for a qualifier like `org.eclipse.jdt.core` that is several hundred entries of noise.

### `eclipse_set_preference`

**Modifies the IDE configuration.**
Writes one preference and returns the previous value, so any change can be undone.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `qualifier` | string, required | | Any preference qualifier. |
| `key` | string, required | | |
| `value` | string | remove the key | Omitting it removes the key, letting the value below it in the lookup order take over. |
| `scope` | `instance` \| `project` | `instance` | |
| `project` | string | | Required for the project scope. |

Any qualifier may be written.
An allowlist of four qualifiers used to stand here and was removed, because it was not a boundary.
Project preferences are ordinary files under `.settings` that `eclipse_write_file` writes anyway, and a CSS snippet carrying an `IEclipsePreferences` block writes any qualifier at all through `eclipse_apply_css`.
A restriction that two other tools of the same server walk around stops the caller with a legitimate need and nobody else, which is what it did.
The real hazard is unchanged and is now carried by the answer instead: a wrongly set compiler or formatter option is invisible in the IDE and confusing for a long time afterwards, so `previous` is the only record that the value was ever something else. Keep it.

Auto-build is special cased. Setting `org.eclipse.core.resources` / `description.autobuilding` goes through `IWorkspaceDescription.setAutoBuilding` rather than writing the raw key, which is the usual way to get this subtly wrong, and the answer says so in `appliedThrough`.

One key is refused all the same: writing `themeid` under `org.eclipse.e4.ui.css.swt.theme` reaches disk, but it is not a way to switch themes.
At the next startup the theme engine resolves the persisted id against the registered themes, and an id that does not resolve falls back to a default that is persisted over the written value.
That cost one reporter a full restart of a five hundred project workspace before anyone noticed what had happened, because the write looked accepted and the answer said nothing.
Switch themes with `eclipse_set_theme`, which activates a registered theme in the running IDE.

### `eclipse_analyze_dependencies`

Compares what a plug-in declares in `Require-Bundle` and `Import-Package` with the bundles its source actually resolves against. Read-only. Takes `project` or `projects` and `maxResults`.

| Field | Meaning |
|---|---|
| `declaredRequireBundle` | as written, with `reexported`, `optional`, `resolved` |
| `actuallyUsed` | bundles supplying at least one type the source resolves against, with a count and a sample |
| `unused` | declared, and nothing in the source resolves to them |
| `viaReexport` | used, but only reachable because a declared bundle reexports them, with the chain |
| `undeclared` | used and reachable neither directly nor through a reexport |

`viaReexport` is the edit list a reexport cleanup needs: those entries have to be declared here before the reexport that supplies them can be dropped.

Usage is computed from **resolved bindings, not import statements**: a fully qualified use has no import, and an import can outlive the last use of it.

**`unused` is not a deletion instruction**, for the same reason `dead` is not in `eclipse_list_declarations`. A bundle can be needed for a class named in `plugin.xml`, an OSGi service, or a `Class.forName` that leaves no type reference. An optional or platform-filtered requirement that does not resolve on this machine is reported with `resolved: false` rather than judged.

### `eclipse_get_bundle_info`

Reports OSGi bundles as PDE resolved them against the active target platform.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `symbolicName` | string | | Exact bundle symbolic name. |
| `namePattern` | string | | Glob over symbolic names. |
| `workspaceOnly` | boolean | `true` | With `false`, target platform bundles are included. |
| `unresolvedOnly` | boolean | `false` | Only bundles that did not resolve. |
| `includeConstraints` | boolean | `true` | List `Require-Bundle` and `Import-Package` with resolution status. |
| `maxResults` | integer, 1 to 2000 | 100 | |

Every `Require-Bundle` and `Import-Package` entry carries `resolved` and, when it resolved, `boundTo`, the bundle or package that actually satisfied it.
That is the difference between this and reading `MANIFEST.MF`: the manifest shows what was asked for and never what was found, so *Cannot resolve plug-in: org.eclipse.opengl* stays a guess until something tells you nothing supplies it.

`platformFilter` and `fragmentHost` come from the resolver rather than from a regex over the manifest, which is also what `eclipse_set_project_state` uses for `platformMismatch`.

### `eclipse_get_target_platform` and `eclipse_set_target_platform`

Reads the active target platform, and sets a target definition as the active one, which is what the *Set as Active Target Platform* link of the target editor does.

`eclipse_get_target_platform` is read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `includeLocations` | boolean | `true` | Report each location with its own resolution status. |
| `includeKnown` | boolean | `false` | List the definitions the IDE knows, workspace `.target` files included, with the memento of each. |
| `maxProblems` | integer, 1 to 1000 | 50 | Cap on the reported bundles that failed to resolve. |

`targetSet` is the difference between a definition being active and PDE falling back to the IDE's own installation: PDE answers with a default definition either way, and only the handle behind it says whether anything was set.

**`eclipse_set_target_platform` changes the IDE.**
It replaces the bundles every plug-in project compiles against, PDE recomputes the plug-in classpaths, and problem markers across the workspace change with it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `file` | string | | The `.target` file, as a workspace path such as `/target-platform/example.target` or an absolute file system path. |
| `memento` | string | | A target handle memento instead of a file, as reported by `includeKnown`. |
| `resolveOnly` | boolean | `false` | Resolve and report, without activating. |
| `wait` | boolean | `true` | |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | Below the tool call timeout, so a slow resolve returns `running` rather than being killed. |
| `includeLocations` | boolean | `true` | |
| `maxProblems` | integer, 1 to 1000 | 50 | |

```json
{"target":"/target-platform/example.target","state":"done","resolveOnly":false,
 "elapsedMillis":48213,"resolveMillis":47019,
 "resolveStatus":{"severity":"OK","message":"OK"},
 "loadStatus":{"severity":"OK","message":"OK"},
 "previous":{"name":"Old target","memento":"..."},
 "definition":{"name":"Example","resolved":true,"bundleCount":1782,"featureCount":41,
   "bundleProblems":[],"bundleProblemCount":0,
   "locations":[{"type":"InstallableUnit","location":"https://download.eclipse.org/releases/2026-06",
     "resolved":true,"bundleCount":1782,"status":{"severity":"OK","message":"OK"}}]}}
```

Resolving a target that is not cached downloads from its p2 repositories and takes minutes, which is longer than a tool call may last, so the work runs as a job.
A call that runs out of `timeoutSeconds` returns `state: "running"` and the job carries on; `eclipse_get_target_platform` then reports it as `lastLoad` until it ends.
Starting a second load cancels the first, the way PDE itself does.

The status is reported with its children rather than as a sentence, because a target that does not resolve fails in one location, and which repository was unreachable or which unit is missing is only in there.
`resolveOnly` answers that question without touching the workspace, which is the difference between checking a `.target` file and committing to it.

A client that wants to activate a target definition of its own can write the `.target` file with `eclipse_write_file` and name it here straight away, without a refresh in between.

### `eclipse_set_bree`

**Rewrites `META-INF/MANIFEST.MF` and `.classpath`.**
Sets the `Bundle-RequiredExecutionEnvironment` of plug-in projects and the JDT compiler settings that have to agree with it, in one operation.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `bree` | string, required | | Execution environment id, e.g. `JavaSE-21`. Must be one the IDE knows. |
| `projects` | array of strings | | Plug-in project names to act on. |
| `namePattern` | string | | Glob over project names, `*` and `?`, case insensitive. |
| `currentBree` | string | any | Only projects currently declaring this environment. |
| `updateCompliance` | boolean | `true` | Also set compiler compliance, source and target. |
| `dryRun` | boolean | `true` | |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"bree":"JavaSE-21","dryRun":false,"total":1,"changed":1,"skipped":0,"truncated":false,"projects":[
  {"name":"com.example.bundle","previousBree":"JavaSE-17","bree":"JavaSE-21",
   "previousJreContainer":"org.eclipse.jdt.launching.JRE_CONTAINER/.../JavaSE-17",
   "compliance":{"compliance":{"from":"17","to":"21"},"source":{"from":"17","to":"21"},
                 "target":{"from":"17","to":"21"}},
   "jreContainer":"org.eclipse.jdt.launching.JRE_CONTAINER/.../JavaSE-21",
   "changed":true,"skippedBecause":null}]}
```

The manifest header is written through PDE's `IBundleProjectDescription`, which is public API.
The JRE container in `.classpath` is then pointed at the new environment explicitly, because `apply()` writes the header and leaves the classpath alone.

The compiler settings come from `IExecutionEnvironment.getComplianceOptions()` rather than from a version table, and only compliance, source and target are written. Setting the header without them leaves a project whose manifest and compiler disagree, which is the state PDE raises a marker for; doing both together is the point of the tool.

Non plug-in projects in the selection are ignored rather than reported as failures. `currentBree` is how you move a whole set off one version. At least one of `projects` or `namePattern` is required.

Note that BREE is the older mechanism: OSGi R7 replaced it with `Require-Capability: osgi.ee`. Eclipse's own bundles still use BREE almost everywhere, which is why this tool writes it, but it is not the modern spelling.

### `eclipse_set_java_version`

**Sets the Java version to compile for, and optionally the JDK behind it.**
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `version` | string, required | | The release to compile for, e.g. `21`. |
| `project` | string | all | Restrict to one project. |
| `compliance` | boolean | `true` | Set source, target and compliance through `JavaCore`. |
| `release` | boolean | | Use the `--release` form. |
| `defaultVm` | boolean | `false` | Also switch the workspace default JDK. |
| `dryRun` | boolean | `true` | |

Two different things are set here and mixing them up is the usual mistake.
`compliance` is what the code is compiled **as**, source, target and compliance together through `JavaCore`, which is what a migration to a newer release changes.
`defaultVm` is which installed JDK the workspace uses where a project's JRE container names no execution environment, and that one is shared: every such project follows it at once.

The JDK is checked before it is chosen, because a JDK can be present and still unable to compile.
One whose `lib/ct.sym` is missing fails every project bound to it with a message about `ct.sym` that names neither a project nor a version.

Note that a target platform overwrites `defaultVm`.
Activating a target definition that names a JRE replaces it, so setting this and then activating such a target puts the target's choice back.

### `eclipse_set_project_state`

**Opens and closes projects.**
Reversible: no files are lost and no project code runs.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `state` | `open` \| `closed`, required | | |
| `projects` | array of strings | | Project names to act on. |
| `namePattern` | string | | Glob over project names, `*` and `?`, case insensitive. |
| `platformMismatch` | boolean | `false` | Only projects whose bundle cannot run on this platform. |
| `dryRun` | boolean | `true` | |
| `force` | boolean | `false` | Close even when open projects depend on the project. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"state":"closed","dryRun":true,"total":2,"changed":1,"skipped":1,"truncated":false,"projects":[
  {"name":"org.eclipse.compare.win32","previousState":"open",
   "platformReason":"Eclipse-PlatformFilter does not match: (& (osgi.ws=win32) (osgi.os=win32))",
   "changed":true,"newState":"closed","skippedBecause":null},
  {"name":"org.eclipse.ui.win32","previousState":"open","openDependents":["org.eclipse.ui.ide"],
   "changed":false,"newState":"open",
   "skippedBecause":"Open projects reference it, and closing it would give them build path errors rather than removing errors. Pass force to close it anyway."}]}
```

At least one of `projects`, `namePattern` or `platformMismatch` is required; the tool refuses to act on every project in the workspace.

`platformMismatch` reads the `Eclipse-PlatformFilter` header from the project's `META-INF/MANIFEST.MF` and evaluates it as an OSGi filter against the running `osgi.ws`, `osgi.os` and `osgi.arch`. That is a declaration, not a guess. Only when a project has no such header does it fall back to looking for a foreign platform token in the name, and `platformReason` then says that it is a heuristic. Name matching alone would work for Eclipse's own naming convention and quietly misfire elsewhere.

Closing a project that open projects reference does not remove errors, it gives the dependents build path errors instead. So `openDependents` is always reported, from `IProject.getReferencingProjects()`, which covers both JDT build path references and PDE required bundles, and closing is refused unless `force` is passed.

**A batch is resolved as a whole.** `getReferencingProjects()` reports the projects that are open right now, so closing a cluster used to refuse every member whose dependents were themselves in the same call: the refusal described a state that would not exist once the call returned, and closing a cluster took one pass per layer of the graph.
The projects a call will actually close are now computed as a fixpoint first, and only dependents that will still be open afterwards block anything. Those appear in `openDependents` as before; the ones closing in the same call appear in `dependentsClosingTogether`, which is reported but never blocks.
Removing one project from the set can block another, which is why this iterates rather than subtracting the selection once, and it resolves the same way for a dry run as for a real one.

### `eclipse_import_project`

**Imports projects that already exist on disk into the workspace**, which is what *File > Import > Existing Projects into Workspace* does.
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `location` | string, required | | Absolute directory to import from. |
| `search` | boolean | `false` | Also look below the directory for more projects. |
| `open` | boolean | `true` | Open the projects after importing them. |
| `dryRun` | boolean | `true` | Report what would be imported without importing it. |
| `maxResults` | integer | 100 | |

Nothing on disk is written or moved.
The project stays where it is and the workspace gains an entry pointing at it.

It works from the `.project` file: a directory that has one is imported, and with `search` it also looks below the directory for more.
**That is the limit and it matters here.**
A Maven or pomless Tycho module has no `.project` until m2e has imported it once, so this cannot bring one back, which is why `eclipse_remove_project` reports `hasProjectFile` before removing rather than after.

A name already taken in the workspace is reported and skipped rather than silently doing nothing, since two projects cannot share a name even when they are different directories.

### `eclipse_remove_project`

**Removes projects from the workspace.**
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `projects` | array of string | | Project names to remove. |
| `namePattern` | string | | Remove every project whose name matches instead. |
| `dryRun` | boolean | `true` | Report what would be removed without removing it. |
| `force` | boolean | `false` | Remove even when open projects reference these. |
| `maxResults` | integer | 200 | |

**Nothing on disk is deleted.**
Only the workspace entry goes and the files stay exactly where they are, which for a workspace pointing at git working trees is the only defensible behaviour, and is why deleting the content is not offered here at all.

That also makes this reversible only by importing the project again.
A project without a `.project` file on disk, such as a pomless Tycho module imported through m2e, cannot be imported back by simple means, so removing one of those is close to permanent.

Open projects that reference the ones being removed are reported and the removal is refused unless `force` is set, because they will lose their build path rather than gain anything.

To stop a project taking part in the build without giving up the ability to bring it back, close it with `eclipse_set_project_state` instead.
That is reversible in one call.

### `eclipse_build`

**Runs builders.**
Builds the workspace or named projects.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `kind` | `incremental` \| `full` \| `clean` | `incremental` | |
| `project` | string | whole workspace | Single project to build. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `wait` | boolean | `true` | Wait for the build before answering. |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | How long to wait before answering with `running`. |
| `returnProblems` | boolean | `true` | Count errors and warnings once the build ended. |
| `refresh` | boolean | `true` | Refresh from disk first, scoped to the named projects. |
| `buildAfterClean` | boolean | `false` | Build again after a clean. |

```json
{"buildId":"build-3","kind":"full","state":"done","scope":"projects","projects":["app"],
 "elapsedMillis":8412,"refreshMillis":204,"buildMillis":8208,"note":null,
 "errors":2,"warnings":17,"builderFailures":[]}
```

Everything slow happens inside the job, the refresh included, so `wait: false` always returns straight away with a `buildId`, and a build longer than `timeoutSeconds` comes back as `state: "running"` rather than holding the request open until the call timeout kills it.
Keep `timeoutSeconds` below that timeout; the default 25 fits under the default 30.

`refreshMillis` and `buildMillis` are reported separately because on a large workspace the refresh can cost more than the build, and a single number hides that.

A `clean` only deletes build state. With auto-build off nothing rebuilds afterwards, so the error count describes an unbuilt workspace rather than a working one; the answer then carries a `note` saying so. `buildAfterClean` rebuilds, the way the *Build immediately* checkbox of *Project > Clean* does.

`builderFailures` carries what went wrong without becoming a problem marker, so that a build whose `JavaBuilder` threw is not reported as a clean one.
It has two sources. Exceptions that reach `IProject.build` are flattened out of the multi status they arrive in. But most builder failures never get that far: `BuildManager` runs builders inside a `SafeRunner`, which catches the exception and writes it to the Error Log, so the build returns normally and there is nothing to catch. Those are picked up by reading the platform log for errors and warnings logged while the build ran.

The second source is correlated by time, not by causation, so anything else the IDE logged during the same window is included too. Over-reporting was the deliberate choice: calling a broken build clean is the worse failure.

### `eclipse_refresh`

Reads changes made outside the IDE into the workspace, and nothing else.
Use it after switching branches, updating submodules or editing through a shell, when you want the IDE to see the new files without also building or reading markers.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string | whole workspace | Single project to refresh. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `wait` | boolean | `true` | Wait for the refresh before answering. |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | |

It runs as a job and answers in the same shape as `eclipse_build`, with `kind: "refresh"`, so `eclipse_get_build_status` reports on it too.
It never counts markers: a refresh does not build, so any count would describe whatever the last build left behind and invite a wrong conclusion.

Refreshing is available on `eclipse_build` and `eclipse_get_problems` as well, but only as a step before something else. With auto-build off, picking up external edits and deciding whether to build are separate decisions, which is why this exists on its own.

### Profiling a launched program

The recording tools work inside the IDE's own JVM: `eclipse_start_flight_recording` builds a `jdk.jfr.Recording` there and `eclipse_start_sampling` reads its `ThreadMXBean`.
Neither can see a program the IDE launches, because that is a separate process.

`eclipse_debug_launch` and `eclipse_run_tests` therefore take `flightRecording`, one of `off`, `default` or `profile`, which appends `-XX:StartFlightRecording` to the launch's VM arguments rather than replacing them.
The decision has to be made before the launch, which is the price of needing no attach mechanism and no external tool.
`dumponexit` is set, so the file is written when the JVM **exits**: it is not there while the program runs, and a program that is killed rather than ended leaves nothing.
The answer carries `flightRecordingFile`, and `eclipse_stop_flight_recording` reads it back through its `file` argument, aggregating it exactly as it aggregates the IDE's own recordings.

For a process that is already running, the answers of the debug tools carry `pid`, from `IProcess.ATTR_PROCESS_ID`.
That is deliberately where this server stops: `jcmd <pid> JFR.start` reaches such a JVM, and wrapping the JDK's own command line tools is not this server's job.

### `eclipse_cancel_build`

**Cancels work in progress.**
Stops what is building: the jobs `eclipse_build` started and the workspace's own auto-build and manual build.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `waitSeconds` | integer, 0 to 20 | 5 | How long to watch for the cancelled jobs to end before answering. |

Three things the answer says on purpose, because a cancel that reports success while the builder carries on is worse than no cancel.

Cancelling is a request, not a kill. A builder stops only where it checks its progress monitor, and one that never checks runs to the end of what it is doing, so `stillRunning` reports what had not ended when the wait was up rather than the tool claiming more than it did.

A cancelled build leaves the workspace partly built, so error and warning counts afterwards describe nothing until something builds again.

`autoBuildEnabled` is always reported, because with auto-build on a cancel buys a pause and not a stop: the next change starts a build again. Turning it off with `eclipse_set_preference` on `description.autobuilding` is what actually stops it coming back, and the note says so.

### `eclipse_get_build_status`

Reports a build started through `eclipse_build`, by `buildId`, or the most recent one when that is omitted.
The answer has the same shape as the one above.
The last 20 builds are kept; asking for an older id is an error, while asking before anything has been built returns `{"state":"none"}` rather than an error.

### `eclipse_pause`

Waits for `millis` and returns. Read-only.
This is the pause between the steps of a screencast a person reads, and the honest form of what `eclipse_wait_until_settled` with `quietRounds` was being used for.
The wait is capped at the call timeout less a margin and the answer says `clamped` when the cap applied, so a longer hold is several calls.
Refused inside an atomic `eclipse_run_script`, where it would freeze the UI thread and nothing would repaint.

### `eclipse_wait_until_quiet`

Waits until the auto-build, the manual build and the refresh jobs have finished and no other job is running, then answers what it waited for and how long each part took. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `timeoutSeconds` | integer | 120 | How long to wait. `1` makes it a status query rather than a wait. |

**This is the tool to call before timing anything.**
After a restart the workspace builds for a minute or two on its own, and a measurement taken in that window measures the build.
Watching the process from outside cannot tell the quiet before the build starts from the quiet after it, which is the mistake this exists to prevent; from inside the two are different job states.

`waitedFor` is empty when nothing was running, which is itself the answer that the IDE was already idle.

With `timeoutSeconds` 1 it is a status query, and unlike `eclipse_get_build_status` it belongs to no client and therefore needs no id, which makes it the way to ask whether the workspace is building while several clients are connected.

It answers before the server's own call timeout runs out, with state `stillBusy` and the jobs that are still going, because a call that is abandoned mid-wait tells the caller nothing at all.
Ask again until the state is `quiet`, which is a loop of a few calls for the build after a restart.

It does **not** cover the Java index.
JDT runs that in a queue of its own outside the job manager, and `eclipse_search_types` with a narrow pattern is what blocks until the index is ready.

### `eclipse_save_workspace`

**Saves the workspace**, which is what the IDE otherwise only does while shutting down.
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `mode` | string | `full` | `full` or `snapshot`. |
| `timeoutSeconds` | integer | 25 | |
| `dryRun` | boolean | `true` | |

A full save is not a read-only operation.
It writes the element tree, the markers and the sync info of every project, moves the save number on, deletes the snapshots and runs the local history pruning, which removes file states by the history policy.
That is the same work an exit does, so it is repeatable and measurable here rather than only observable once per process.

The interesting part of the answer is the status: each save participant contributes its own child status, so a plug-in that fails or complains while saving is named instead of vanishing into one number.

`mode` `snapshot` writes only what changed since the last full save and skips the pruning, which is what the workspace does periodically by itself.

**A series of saves does not measure a shutdown save.**
The expensive part of the first one is the delta chain, a tree per save participant and per builder of every open project, and once those trees are unchanged the comparison short circuits, so every save after the first is systematically cheaper and stays cheap even across a restart, because the builders read their trees back from the same chain.
Measured here at about 1.5 seconds repeated against nearly 4 seconds for the first save of a session.

The duration is reported in two parts, because the save takes the workspace root rule and therefore queues behind a running build.
`waitedForRuleMillis` is that queueing and `saveMillis` is the save, and only the second is comparable between runs.

### `eclipse_find_references`

`queries` asks about many elements in one call and returns **counts only**: `[{typeName, memberName?, paramTypes?, accessKind?}]`, and per query `total`, `source`, `binary` and `declaration`, plus `byMember` when the name has more than one overload. A dead code sweep asks "how many references" about hundreds of candidates and needs the locations for almost none of them, so returning matches would make the answer enormous to save the round trips that were the problem. A name that does not resolve fails that query alone, not the batch. Use the single form when you need the match locations.


Finds all references to a Java type, method or field across the workspace with the JDT search engine.
Far more accurate than a text search, because it resolves overloads and inheritance.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `memberName` | string | the type itself | Method or field name. Every overload is searched unless `paramTypes` narrows it. |
| `paramTypes` | array of string | all overloads | Selects one overload by its parameter types, simple or qualified: `["String","int","int"]`. An empty array is the no-argument overload. |
| `project` | string | whole workspace | Project used to resolve the type and to scope the search. |
| `maxResults` | integer, 1 to 2000 | 200 | |
| `accessKind` | `all` \| `read` \| `write` | `all` | Restrict to read or write accesses. Fields only. |

```json
{"resolved":"org.eclipse.jface.viewers.TreeViewer#setInput","accessKind":"all",
 "total":17,"byOrigin":{"source":15,"binary":2},"truncated":false,
 "matches":[{"path":"/app/src/com/example/View.java","project":"app","line":88,
             "offset":2451,"length":8,"kind":null,"signature":"setInput(Object)",
             "location":"/home/me/ws/app/src/com/example/View.java",
             "enclosingElement":"com.example.View.createPartControl(Composite)"}]}
```

Every match carries an `origin`. A `source` match has a workspace `path` and a `project`, plus the `location` of the file on disk. A `binary` match is inside a compiled jar on some project's build path, and reports the jar as `library` with `path`, `project` and `location` all null.

**Overloads.**
Every match carries the `signature` of the overload it belongs to, and `byMember` gives one count per overload:

```json
{"resolved":"org.eclipse.swt.graphics.ImageDataLoader#loadBySize","total":16,
 "byMember":[{"signature":"loadBySize(InputStream, int, int)","total":16},
             {"signature":"loadBySize(String, int, int)","total":0}]}
```

Without that split a count of 16 cannot tell "this overload is dead and its sibling has sixteen callers" from "both are live", which is the whole question a dead code sweep asks. `paramTypes` searches one overload alone, and `resolved` then names it in full.

**A name declared more than once.**
SWT declares `org.eclipse.swt.graphics.Image` once per window system, and each fragment puts its own copy on the build path.
A JDT search built from an element carries that element as its focus, which narrows the index to the projects that can see it, so a search bound to the gtk copy answers nothing at all about the win32 and cocoa call sites, silently and with `truncated` false.
Every copy is therefore resolved and searched, and `declaredIn` lists the source folders they came from whenever there is more than one.
This is the case where an under-count is dangerous: live code looks dead, in exactly the cross-platform situation nobody can check by eye.

**Linked files.**
One physical file reached through several projects that link it counts once. The SWT fragments share a single copy of each source file across seven projects, so an undeduplicated `total` overstates a call site sevenfold. `linkedDuplicates` says how many matches were folded away, always, so that nothing folded cannot be confused with the field being absent, and `alsoIn` on the surviving match names the other projects. Because the workspace `path` of a linked file does not exist under its project on disk, `location` gives the resolved filesystem path, which is the one to read.

That distinction is not cosmetic. `SearchMatch.getResource()` returns the *project that owns the classpath entry* for a match inside a jar, so the raw path is a bare project name with no file. Reported as-is, a hit inside `org.eclipse.jdt.ui.jar` looks like a source reference in whichever project happens to depend on that jar, and nothing marks it as second hand. Judge "how many consumers does this API have" from the `source` count in `byOrigin`.

The `project` argument scopes the search to that project **and everything on its build path**, which includes other workspace projects and jars. It narrows less than it looks.

A name based search is broader work than a binding based one, so asking for references to a very common JDK type across a large workspace is slow. Pass `project` to scope it.

An unresolvable type name comes back as an error result naming the type, not as a protocol error.

**Reads and writes.**
When the member resolves to a field, the answer also carries a `byKind` summary and a `kind` on every match, without a second call:

```json
{"resolved":"com.example.Cache#lastSelection","accessKind":"all","total":4,
 "byKind":{"read":0,"write":4},"truncated":false,"matches":[...]}
```

This is the one thing a text search cannot approximate: a field written in four places and read in none is dead, while every text tool sees four live occurrences.
`kind` is `read`, `write`, `readWrite` for a compound assignment such as `count += 1`, or `null` for anything that is not a field.

Two details that matter if you act on the numbers.
A field initializer is a write access but a declaration rather than a reference, so it is counted in `byKind` while being absent from `total` and from `matches`; the counts need not sum.
And `read` or `write` on a type or a method is an error rather than an empty answer, because only fields are read and written.

### `eclipse_run_command` and `eclipse_get_command_output`

**Runs arbitrary code**, with the rights of the user running the IDE.
This is the one tool here that is not the IDE acting on itself, and it exists because a build that produces a p2 repository, a Maven or Tycho run, is otherwise out of reach: with it the chain becomes build, `eclipse_add_repository`, `eclipse_install`, `eclipse_restart`.

**There is no allowlist of directories, and the one that used to stand here was removed.**
It gated the tool behind a list of roots that had to be filled in under *Preferences > General > MCP Server*, empty by default, and the effect was not safety.
A client that is refused here still has its own shell: it runs the same command outside the IDE, in the same account, with the same rights, and the server loses the one thing it was contributing, which is that the build and the workspace are the same picture.
That is measured rather than argued. The refusal read as "running commands is switched off", and what followed it was the command running anyway somewhere this server could not see.
So the guard never removed the capability, it removed the visibility, and the audit trail with it: what runs through this tool is recorded, pollable through `eclipse_get_command_output`, and bounded by a working directory the caller had to name.
This is the same conclusion the `eclipse_set_preference` allowlist reached, for the same reason.

The directory stays required, absolute and real, which is not a boundary and is not meant as one: it keeps a command from inheriting whatever working directory the IDE happens to have, so a relative path in a build script resolves where the caller meant.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `directory` | string, required | | Absolute working directory. The command runs here and nowhere else. |
| `command` | string | | Command line, run through the shell. |
| `args` | array of string | | Command and arguments one by one, no shell. Wins over `command`. |
| `environment` | object | | Extra variables, merged over the IDE's own. |
| `wait` | boolean | `false` | Wait instead of returning a handle. |

A build takes minutes and the call timeout is 30 seconds, so this runs as a job and hands back a `commandId` to poll, the same shape as `eclipse_build` and `eclipse_install`.

```json
{"commandId":"command-1","command":["/bin/sh","-c","mvn clean verify"],
 "directory":"/home/me/git/themes","state":"failed","exitCode":1,
 "elapsedMillis":184213,"droppedLines":0,"output":"[ERROR] ..."}
```

stdout and stderr are merged, because whoever reads a build log wants the failure in the same stream as the step that led to it.
The last 2000 lines are kept and `droppedLines` says how many fell out, since a Tycho log is long and the useful part is at the end.
`eclipse_get_command_output` takes `tailLines` to see further back, `wait` to block, and `cancel` to stop a run, which ends the process and everything it started.

### `eclipse_set_shell_bounds` and `eclipse_set_part_state`

**Change what the user sees**, the way `eclipse_set_ide_visibility` does.

`eclipse_set_shell_bounds` takes `shellTitle`, `x`, `y`, `width`, `height` and `maximized`, any subset.
`eclipse_set_part_state` takes `part` and a `state` of `maximized`, `minimized`, `restored` or `activated`; `activated` focuses an editor without knowing its file path, which `eclipse_open` needs.

**`activated` is not one of the window states.** A part has three of those, `restored`, `maximized` and `minimized`, and activating changes none of them: it brings the part to the front of its stack and gives it focus. So the answer to `activated` reports `requested`, `focusGiven` and a `state` that is whatever window state the part kept, usually `restored`. Reading only `state` back makes a successful activation look like a request that was ignored, which is why the other two fields are there.

Both report the previous value, `previousBounds` and `previousMaximized` or `previousState`, so a caller can put the IDE back exactly as it found it. That is what makes them usable on somebody's running workbench rather than only on a throwaway one.

They exist for the states that only a size change produces, and that nothing else here can reach: tab overflow and its chevron, text truncation, scrollbars, sash and border rendering between stacks, reflowing form layouts, and the trim stack a minimised part uses. Each is drawn by a different set of CSS selectors.

### `eclipse_get_widget_tree` and `eclipse_inspect_widget`

Read-only. What a widget is, and what the CSS engine made of it.

`eclipse_get_widget_tree` walks a part or a shell and reports each widget's class, bounds, CSS id, CSS class and the `path` that addresses it. `filter` narrows by class name, so "which Trees does this view contain and what are their ids" is one call.

**A known gap in `eclipse_screenshot`.** `includeToolbar` captures the part stack, but a widget print rooted anywhere inside the window does not paint the `CTabFolder`'s `topRight` children, which are the view toolbar, the view menu and the min and max buttons. Rooting at the folder, at its parent and at the window's content composite were all tried and all miss them, and nothing in the answer says they are absent, so an empty toolbar region reads as "there is no toolbar" rather than "the capture missed it". The reliable way to see one is to capture `target=shell` and crop to the bounds `eclipse_get_widget_tree` reports for the toolbar, which is why the two tools are worth using together.

`eclipse_inspect_widget` takes a `path` from that tree and adds the CSS element it maps to, the ancestor chain, and what the engine resolved for each requested property, under an optional `pseudo` class.

```json
{"found":true,"path":"0/1","class":"org.eclipse.swt.widgets.ToolBar",
 "cssId":null,"cssClass":"MToolBar","cssElement":"ToolBar",
 "computed":{"background-color":"#1F1F1F","color":null},
 "declared":{"background-color":"inherit","color":null},
 "origin":{"background-color":"css","color":"unset"},
 "cssDeclaration":"background-color: inherit;"}
```

`includeItems` enumerates `Item`s as well as `Control`s: the buttons of a toolbar, the tabs of a folder, the columns of a table or tree. A `ToolItem` is not a `Control`, so it appears in no walk over the control hierarchy, while the CSS engine styles each one as its own element; without this there is no way to address a toolbar button at all, and `pseudo` on the inspector is unusable for exactly the toggle-state question it exists for. Item paths carry an `i` prefix, as in `2/i0`, and `kind` says `control` or `item` so the two are never confused. Off by default because a `Menu` can be large.

**Paths, not coordinates.** A path is slash separated indices. Screen coordinates were the obvious interface and are the wrong one: a client cannot point at anything, and a pixel does not survive a resize or a restart where an index does.

A `null` computed value for a property the theme sets is the useful signal: either no rule applied, or a `#token` reference resolved to nothing, which otherwise shows up only as something rendering black or white.

**`computed` is not evidence that a rule ran.** The engine answers `retrieveCSSProperty` by reading the value back off the widget, so a `ToolBar` that no rule matches reports the window system's grey exactly the way a themed one reports the theme's grey, and the two are indistinguishable unless you already know the palette. `declared` is the other half: the merged declaration the matching rules produce for that element, `origin` says `css`, `widget` or `unset` per property, and `cssDeclaration` is the whole declaration, including properties that were not asked about. A `declared` of `inherit` beside a `computed` colour is how an inherited value that froze against the wrong parent shows itself.

**A background image beats every value reported here.** A control whose `getBackgroundImage()` is set paints that image, so the answer carries `backgroundImage` and nothing else in it is what is on screen. The computed colour is not the colour under the image either: `CTabFolder.updateBkImages` calls `control.setBackground(null)` on each child before handing it the gradient image (`CTabFolder.java:4118`), so what `getBackground()` reads back afterwards is the window system's default for that widget, with no relationship to the theme. Three colours are then in play for one widget and the computed one is the least relevant, which is a wrong conclusion this project has already watched somebody reach and hold.

A stylesheet declaring a two-stop `swt-unselected-tabs-color` is enough to get there: `CSSPropertyUnselectedTabsSWTHandler` branches on the value being a list rather than a primitive, takes the gradient API, and `gradientColors` then stays non-null permanently.

**What these do not do.** They do not report which rule matched or which stylesheet it came from. The engine keeps the merged declaration and not its source, so finding the rule still means reading the stylesheets, or applying a candidate rule with `eclipse_apply_css` and inspecting again.

### `eclipse_select_tab`

**Selects a tab by its widget path**, the one `eclipse_get_widget_tree` reports with `includeItems`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Path of a `CTabItem` or `TabItem`, as in `0/0/0/i2`, or the path of the folder plus `index`. |
| `index` | integer | | Which item of the folder, when `path` names the folder. |
| `part` | string | active | Part to look in. |
| `shellTitle` | string | | Shell to look in instead. |
| `notify` | boolean | `true` | Notify the selection listeners as if a person had clicked. |

This addresses the folder rather than the editor, which is what makes it work at all.
A multi page editor may be a `MultiPageEditorPart`, or a `FormEditor`, or neither, and the e4 model editor for instance builds its Form, List and XMI pages into a `CTabFolder` of its own, so no editor API reaches them.

A page that is not selected is not rendered.
`eclipse_screenshot` refuses it and `eclipse_get_widget_tree` reports it with zero bounds, so selecting it first is the only way to see it.

The selection listeners are notified as if a person had clicked, because `setSelection` alone moves the highlight without telling the editor to build the page.

### `eclipse_apply_css`

**Changes what the user sees.** Applies a CSS snippet on top of the current theme and re-styles every shell, the way PDE's CSS scratch pad does.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `css` | string | | The stylesheet to apply. Appended after the theme's own sheets, so it wins ties of equal specificity. |
| `reset` | boolean | `false` | Take the snippet back and leave the IDE on the unmodified theme. Give this instead of `css`, not with it. |

```json
{"applied":true,"previousSnippet":null,"theme":"com.vogella.eclipse.themes.nord",
 "themeReapplied":true,"engines":1,"rules":3,"errors":[],"elapsedMillis":412}
```

Nothing is written to disk. The snippet lives in the engine, and a restart, a theme change or the plug-in stopping drops it; `McpUiPlugin.stop` takes it back for the same reason `eclipse_set_ide_visibility` restores a hidden window, since a snippet can leave the IDE unreadable.

Every call re-applies the current theme first, so snippets replace each other instead of piling up, and `reset` is that step alone. `rules` says how many rules were parsed, and `errors` carries what the engine's error handler reported, which is where a selector typo surfaces; a snippet that parses to zero rules is the answer to "why did nothing change".

Preference rules take effect when a theme is activated: after a real activation a key such as `org.eclipse.jdt.ui/java_keyword` carries exactly the value the theme's CSS declares.
This tool cannot activate a theme, but it routes such a block through the engine's own preference styling all the same and reads every declared key back afterwards, so the answer says what took rather than assuming.
The engine leaves a value it did not set itself alone until the theme has changed this session: a key that kept its value lands in `preferenceRules` under `unchangedKeys`, with the value that is there now, `applied` stays false until every declared key took, and `eclipse_set_theme` is what applies them outright.
A later theme change takes those overrides back, exactly as it takes the snippet itself away.

This is what turns a theme question into an experiment. Whether a selector matches at all is not something `eclipse_inspect_widget` can answer on its own, and rebuilding a theme plug-in and restarting for every attempt is the alternative: apply a rule with an unmistakable colour, inspect, and read `origin`.

### `eclipse_list_themes` and `eclipse_set_theme`

`eclipse_list_themes` is read only. It lists every theme the running IDE's CSS engine has registered, with `id`, `label` and which one is `active`.

Discovery is the point: a theme id is easy to misremember, and grepping `plugin.xml` files across a workspace for `org.eclipse.e4.ui.css.swt.theme` contributions was previously the only way to learn that `com.vogella.eclipse.themes.githubdark` is not spelled with another dot.

**`eclipse_set_theme` changes what the user sees**, across the whole IDE, and with `persist`, the default, the choice survives restarts.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `theme` | string, required | | Theme id or label, from `eclipse_list_themes`. |
| `persist` | boolean | `true` | Remember the choice across restarts; false switches this session only. |

The name is resolved by exact id, then exact label, then substring, stopping at the first step that matches anything, and an ambiguous name is refused with the candidates rather than guessed.
The answer reports `previousThemeId`, so the old theme can be put back exactly, which is what makes the tool usable on somebody's running workbench.

A theme id that is not currently registered is refused rather than passed to the engine.
Handed one, the engine leaves the current theme up and writes only a platform log warning nobody sees, while a persisted id it cannot resolve at the next startup gets replaced by a fallback, which is exactly the silent failure this refusal exists to prevent.
A bundle installed into the running IDE contributes its themes only after the restart that activates that install, so a freshly installed theme cannot be selected yet; see `eclipse_register_theme` for the way around that.

A switch restyles every shell in the IDE and is not instant, so the tool waits up to 25 seconds.
When that runs out the answer says `timedOut` and that the switch may have completed anyway; `eclipse_list_themes` settles which theme is active before retrying.

A theme change drops any snippet `eclipse_apply_css` put on top and takes its `IEclipsePreferences` overrides back with it, and it is also what makes such a block take effect reliably in the first place.

### `eclipse_register_theme`

Makes a theme selectable in the running IDE without a restart, by registering a stylesheet that already exists on disk.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `id` | string, required | | Id to register the theme under. |
| `label` | string, required | | Label shown wherever themes are listed. |
| `css` | string, required | | Stylesheet as a file path or `file:` URI. |

**It installs nothing.**
The registration lives for this session only and is gone when the IDE restarts, and the engine reads the stylesheet from where it is on every activation, so the file has to stay where it is for as long as the theme is used.

This closes the loop for iterating on a theme bundle: build it, install the bundle into the running IDE, register its css here, switch with `eclipse_set_theme`, screenshot.
The bundle's own contribution lands at the next startup, because the theme engine reads the extension registry once, when it is constructed, and never again; registering the stylesheet by hand is the only way to use it before then.

### `eclipse_list_theme_definitions`

Lists the `org.eclipse.ui.themes` `colorDefinition` and `fontDefinition` entries registered in **this running IDE**. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `kind` | string | `all` | `colors`, `fonts` or `all`. |
| `idPattern` | string | | Regular expression matched anywhere in the id, e.g. `tag|comment|string`. |
| `categoryId` | string | | Only definitions hanging under this theme element category. |
| `bundleFilter` | string | | Regular expression matched anywhere in the contributing bundle's symbolic name, e.g. `pde|jdt`. |
| `onlyOverridden` | boolean | `false` | Only definitions the active theme resolves differently from their declared literal. |
| `countOnly` | boolean | `false` | The totals per kind and no entries. |
| `maxResults` | integer | 200 | Applied to each kind separately. |

Each entry carries the id, the label, the category id and label, the value the declaration asks for, the value the active theme resolves it to, `isEditable` and the contributing bundle.

The answer names two themes, because two theme systems sit on top of each other and they disagree.
`activeThemeId` is the workbench `ITheme` the colour and font registries belong to, and `activeCssThemeId` is the e4 CSS theme that repaints the IDE.
A visibly dark IDE normally still reports `org.eclipse.ui.defaultTheme` as its workbench theme, so reporting only the first would describe a light IDE that is not there.

Two of those cannot be answered by grepping a source tree, which is the reason this exists.
Definitions contributed by installed bundles that are in no workspace project are invisible to a grep, and the resolved value is a property of the active theme rather than of any file.

The declared and the resolved value are reported separately on purpose, because they disagree more often than expected.
`isEditable` false takes a definition off the preference page but does not stop the CSS path in `ThemeElementHelper.populateDefinition` overwriting it, so what a declaration asks for and what the IDE actually draws are different questions.

`overridden` says whether the two differ.
It is omitted rather than guessed when the declaration has no literal to compare against, which is the case for `defaultsTo`, `colorFactory` and OS colour names.

The declaration is read from the extension registry rather than from `IThemeRegistry`, which is internal to the workbench, and the resolved value from the public `ITheme` registries, so this depends on no workbench internals.

### `eclipse_get_installation`

Reports the product, the installed feature groups with their versions, and the configuration timestamps this installation can be reverted to. Changes nothing.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `filter` | string | everything | Only groups whose id or name contains this text, case insensitive. |
| `maxResults` | integer, 1 to 2000 | 100 | |
| `timestamps` | boolean | `false` | Also list the revert points, newest first. |

```json
{"product":{"id":"org.eclipse.sdk.ide","version":"4.41.0.v20260821"},
 "profile":"SDKProfile","currentTimestamp":1787542100000,
 "total":37,"matched":1,"truncated":false,
 "features":[{"id":"com.vogella.eclipse.mcp.feature.feature.group","version":"0.2.0.202608240330","name":"Eclipse MCP Server"}]}
```

This is the tool that confirms an install or an update actually landed, and it is the only one that answers "which version is this IDE running".
The neighbouring tools look like they should and do not: `eclipse_check_for_updates` only reports units that *have* an update, so it says nothing at all when everything is current, and `eclipse_get_bundle_info` describes the active **target platform** rather than the running installation.
`currentTimestamp` is what `eclipse_update` reports as `previousConfiguration`, so a caller can name what a revert would go back to.

### `eclipse_add_repository` and `eclipse_remove_repository`

**Changes IDE configuration.**
Adds a p2 repository so that `eclipse_install` can install from it, and removes one again.
Both are dry runs unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `url` | string, required | | Repository URL. |
| `dryRun` | boolean | `true` | Read and report without changing anything. |
| `refresh` | boolean | `true` | Add only. Re-read the metadata of a URL that is already configured. |
| `maxUnits` | integer, 1 to 200 | 10 | Add only. How many unit ids to list; `groupCount` is always complete. |

```json
{"url":"file:/home/me/git/themes/update-site/repo/target/repository","name":"Themes",
 "dryRun":true,"added":false,"alreadyConfigured":false,"refreshed":false,
 "groupCount":2,"truncated":false,
 "groups":[{"id":"com.vogella.eclipse.themes.feature.group","version":"1.0.0.202608231900"}]}
```

**Why there is no allowlist.**
`eclipse_install` still refuses a URL the IDE is not configured with, so this tool is what makes a freshly built repository installable at all.
An earlier version bounded it with a preference naming acceptable URL prefixes. That is gone: the same feature ships `eclipse_run_command`, and anything that can run a shell command can already do strictly more than install a p2 feature, so guarding the smaller capability while the larger one is open was friction rather than a boundary.
What remains is the dry run, which is a "show me what this contains" step rather than a permission gate, and it stays on by default.

Removal is unguarded for the same reason, and it will just as happily remove a site that was configured by hand, so read the dry run first. Nothing installed from the repository is uninstalled.

`refresh` exists because p2 caches metadata per URL. Rebuilding into the same `target/repository` leaves the path unchanged, so without a refresh the new build looks identical to the old one, which is the same invisible-stale-cache trap `eclipse_check_for_updates` guards against.

### `eclipse_add_git_repository`

**Registers a git repository that is already on disk with EGit**, which is what *Add an existing local Git repository* in the Git Repositories view does.
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `action` | string | `add` | `add`, `remove` or `list`. |
| `directory` | string | | Working tree or `.git` directory. Required for `add` and `remove`. |
| `connectProjects` | boolean | `false` | Also connect the open workspace projects inside the repository, which is what *Team > Share Project* does. |
| `dryRun` | boolean | `true` | Report what would be registered without registering it. |

**Nothing is cloned and nothing is fetched.**
This reaches no network and creates no repository.
Clone with `eclipse_run_command` and register the result here, which keeps credential handling where the user already configured it.

Without this the other git tools are half blind.
`eclipse_get_git_status` resolves a project through EGit's own mapping, so a project whose repository the IDE has never been told about resolves to nothing, and the answer is that no repository was found rather than that it was never registered.

`action` `remove` unregisters a repository **without deleting anything on disk**: the working tree and its history are untouched and it can be registered again.
`action` `list` reports what is registered.

Registering a repository twice is reported as already registered rather than failing, and a path that is no repository is refused before anything is written.

### `eclipse_checkout` and `eclipse_get_git_status`

**`eclipse_checkout` changes the working tree**, and is a dry run unless `dryRun` is set to `false`. Both need EGit; without it they say so rather than the bundle failing to resolve.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `target` | string, required | | Branch, tag or commit. `eclipse_checkout` only. |
| `project` | string | | Project whose repository, resolved as the Git Repositories view resolves it. |
| `directory` | string | | Working tree or `.git` directory, for a repository outside the workspace. |
| `dryRun` | boolean | `true` | `eclipse_checkout` only. |

The switch goes through EGit's `BranchOperation` rather than running `git`, and that is the entire point.
A checkout run outside the IDE leaves the workspace believing the old files are still there, so everything derived from them, problem markers above all, is stale until something refreshes.
EGit runs the switch as a workspace operation, so the affected projects refresh as part of it and that window never opens.
It also refuses a switch that would conflict instead of leaving a half completed one behind, and reports the conflicting paths.

`eclipse_get_git_status` reports `branch`, `head`, `state`, `clean` and the modified, untracked and conflicting paths.
Record `head` alongside a problem set: comparing errors across branches without knowing which commit each set belongs to is how one branch's failures get attributed to another.

### `eclipse_fetch_pull_request`

**Changes the repository and the working tree**, and is a dry run unless `dryRun` is set to `false`.
Fetches a GitHub pull request into a local branch and checks it out, which is what *Fetch GitHub Pull Request* in the Git Repositories view does.
Needs EGit.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `number` | integer, required | | The pull request number. |
| `project` | string | | Project whose repository, resolved as the Git Repositories view resolves it. |
| `directory` | string | | Working tree or `.git` directory, for a repository outside the workspace. |
| `remote` | string | `origin` | The remote that points at the repository the pull request was opened against. |
| `branch` | string | | Name for the local branch. Defaults to the branch the pull request was opened from. |
| `checkout` | boolean | `true` | Switch to the branch after fetching. |
| `dryRun` | boolean | `true` | Report what would happen, including whether the remote advertises the pull request. |

GitHub advertises every pull request as the git ref `refs/pull/N/head`, so the fetch is a plain git fetch through EGit's `FetchOperation`, with the credentials the user already configured, and works without any token.

The local branch is named after the branch the pull request was opened from, which is the one thing git alone cannot tell.
The GitHub REST API is asked for it, with `GH_TOKEN` or `GITHUB_TOKEN` when set; a public repository needs no token.
When the API cannot be reached, the branch falls back to `pr-N`, `branchSource` says `fallback` and `apiError` says why.
`pullRequest` carries the title, state, author, head and base branches when the API answered.

A local branch of that name that already exists is reused when it points at the same commit, fast-forwarded when the pull request gained commits, and refused when it has commits of its own.
The fast-forward of the checked out branch goes through EGit's `MergeOperation`, so the working tree follows and the affected projects refresh.
`branchAction` reports `created`, `unchanged` or `fastForwarded`.

The dry run asks the remote for its advertised refs, so `advertised` false means the number is wrong or the remote is not the repository the pull request was opened against, before anything is downloaded.

Nothing is merged into the current branch and nothing is pushed.

### `eclipse_run_tests` and `eclipse_get_test_results`

**Runs project code.**
Runs JUnit tests through the IDE's own test runner and reports the failures with their stack traces, expected and actual values.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string, required | | Project holding the tests. |
| `testClass` | string | the whole project | Fully qualified test class. |
| `testMethod` | string | | Single method of `testClass`. |
| `dryRun` | boolean | `false` | List the test types that would run, without running them. |
| `wait` | boolean | `true` | |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | |

The JUnit version is detected from the project's own build path and the runtime classpath is the one *Run As > JUnit Test* would use, so nothing has to be configured and no JUnit dependency is resolved here. The launch configuration is never saved, so a run started this way does not appear in the user's launch history.

`eclipse_get_test_results` reports a run by `runId`, or the most recent, with counts and the failing cases. Passing tests are omitted unless `includePassed` is set, because the failures are what the question was about.

| `pluginTest` | `auto` \| `true` \| `false` | `auto` | Run as a JUnit Plug-in Test. `auto` uses it for plug-in projects. |
| `ui` | boolean | `false` | Use the UI test application, which opens a workbench window. |
| `runtimeWorkspace` | string | a sibling `mcp-junit-workspace` | Workspace for the launched platform, cleared each run. |

A **plug-in project is run as a JUnit Plug-in Test by default**, launching a second Eclipse with a running platform in its own cleared workspace, through `org.eclipse.pde.ui.JunitLaunchConfig`. That type is declared by `org.eclipse.pde.launching`, which despite the historical id has no UI dependency, so it works headlessly.

This matters because the alternative is not a slower answer but a wrong one: tests needing OSGi fail under a plain JUnit launch with `The application has not been initialized`, a null `IExtensionRegistry` or `NoClassDefFoundError`, which read as broken tests rather than as the platform being absent. `launchedAs` says which launcher ran, and forcing `pluginTest: false` on a plug-in project adds a `caveat` explaining why the results are suspect.

The **UI test application is opt-in**. It opens a workbench window on the user's screen, and a launched IDE should never be a surprise. The runtime workspace is cleared without asking, since a prompt would block a call nobody is watching.

Results are collected through `JUnitCore.addTestRunListener`, which is global and fires for every run in the IDE. Runs are matched by a launch configuration name generated per run, so a test run someone starts at the keyboard is never reported as one of ours.

With `debug` set, the tests launch in debug mode instead of plain run.
That is the highest value combination these tools have: set an exception breakpoint, run the failing test under the debugger, and read the state at the moment of failure through `eclipse_debug_status`, `eclipse_debug_get_frames` and `eclipse_debug_evaluate`.
The launch appears as a debug session with its own `sessionId`; nothing else about the run changes.

### `eclipse_list_breakpoints` and `eclipse_set_breakpoint`

The breakpoint list of the workspace, read on one side and edited on the other. Nothing here touches a running program or a file; breakpoints live beside the IDE, and Eclipse keeps them across restarts.

`eclipse_list_breakpoints` takes `filter` (substring of the type name) and `maxResults`.

```json
{"total":1,"truncated":false,"breakpoints":[
  {"id":"bp-42","kind":"line","typeName":"sample.Main","line":4,
   "enabled":true,"installed":false,"hitCount":-1,"condition":null,
   "suspendPolicy":"thread","resource":"/app/src/sample/Main.java"}]}
```

Exception breakpoints report `caught` and `uncaught` as well.
`id` is derived from the marker id of the breakpoint's marker, so it is stable within a session and never an index into a list that changes when a breakpoint is added; it is what `eclipse_set_breakpoint` addresses one by.

**`eclipse_set_breakpoint` changes the breakpoint list.**

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `type` | string | | Fully qualified type name for a line breakpoint. Required unless `id` is given. |
| `line` | integer, 1 based | | Present means a line breakpoint. |
| `exception` | string | | Fully qualified exception type. Mutually exclusive with `line`. Goes into `type` when creating. |
| `caught` / `uncaught` | boolean | `false` / `true` | Exception breakpoints only. An uncaught exception is what a caller almost always wants; catching every caught one stops the world constantly. |
| `condition` | string | | Java expression that has to be true to suspend. |
| `hitCount` | integer | | Suspend only on the nth hit. |
| `suspendPolicy` | `thread` \| `vm` | `thread` | Suspend the hitting thread or the whole VM. |
| `enabled` | boolean | `true` | On create. On update an absent `enabled` leaves the current state. |
| `remove` | boolean | `false` | Removes the named or matched breakpoint and ignores every other argument. |
| `id` | string | | Address an existing breakpoint instead of matching by type and line. |

Setting an existing breakpoint again updates it rather than duplicating it, and `created` versus `updated` says which happened.
Moving an existing line breakpoint to another line is refused with the old position named, because updating would silently leave two armed positions behind where the caller asked for one.

Two honesty rules shape the answer:

**A breakpoint that is not installed will never be hit.**
JDT accepts a line breakpoint on a line with no executable code without complaint and then never installs it.
So the answer reports `installed` from the breakpoint itself, and whenever that comes back `false` a note says plainly what it means: with no session running it cannot install yet, which is expected, but if it is still not installed once a session runs, the chosen line carries no executable code and the breakpoint is decoration.
When a session *is* already running and the breakpoint still did not install, the note says that outright.

The breakpoint attaches to the file the workspace actually has the type in, found through the Java model, so it shows up at the right place in the editor's marker bar.
For a type that lives in a jar there is no such file, and the breakpoint attaches to the workspace root, which is what JDT expects.

### `eclipse_list_launch_configurations`

Lists the launch configurations of this IDE with their type, the modes they support and whether they are stored in the workspace or in a project. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeFilter` | string | | Only configurations of this type. |
| `nameFilter` | string | | Only configurations whose name matches. |
| `includeTypes` | boolean | `true` | Also list the launch configuration types. |
| `includeAttributes` | boolean | `false` | Include each configuration's attributes. |
| `maxResults` | integer | 100 | |

`eclipse_debug_launch` takes a configuration by **name**, and without this the names can only be found by reading `.metadata/.plugins/org.eclipse.debug.core/.launches` from the file system, which is where this server should not send anybody.

It also lists the launch configuration **types**, which is what says whether this IDE can start an Eclipse Application or a plug-in test at all.
A runtime workbench has no main class, so `project` plus `mainType` cannot express it and an existing configuration is the only way in.

Configurations this server created for its own launches are marked, since they are transient and belong to nobody's saved launches.

### `eclipse_debug_launch`

**Starts a process**: this runs project code under the IDE's debugger, and anything `main` does, the debugged program does.
It answers with a `sessionId` and the state at the moment of answering.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `configuration` | string | | Name of an existing launch configuration to start in debug mode. Unknown names are refused with the list. |
| `project` + `mainType` | string | | Build a Java Application launch on the fly instead of naming a saved one. |
| `arguments` / `vmArguments` | string | | Program arguments and VM arguments. |
| `stopInMain` | boolean | `false` | Suspend at the first executable line of `main`. |
| `autoTerminateAfterSeconds` | integer | 900 | Terminate the program after this long, finished or not. Only sessions this server started are ever terminated. |
| `waitForSuspendSeconds` | integer, 0 to 25 | 20 | Wait for the first suspend before answering. |

A launch built from `project` and `mainType` is transient: it never appears in the user's saved configurations or launch history.
Launching happens in a job, because creating the JVM takes seconds; the answer waits for the launch to register first and reports a failure as what failed.

Sessions started by hand in the IDE, and launches made by `eclipse_run_tests`, get ids too: the registry listens to the launch manager, not to this one tool.
"The user is stopped at a breakpoint right now, what is going on" is one of the questions this exists for, and it does not require that the session came from here.
Terminated sessions stay listed briefly with `terminated: true`, so a caller polling sees what happened.

Sessions this server started are terminated again when the plug-in stops, or after `autoTerminateAfterSeconds`, whichever comes first.
A suspended JVM nobody can see is exactly the kind of mess a hidden IDE can produce otherwise.
Sessions the user started are never terminated, at any point.

### `eclipse_debug_status`

Read only. Lists the debug sessions this IDE knows and their threads: per session whether it was started by MCP, whether it has terminated and whether anything is suspended, and per suspended thread the breakpoint that stopped it and the top frame as `declaringType.method(File.java:123)`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `sessionId` | string | all sessions | Narrow to one session. |
| `waitForSuspendSeconds` | integer, 0 to 25 | 0 | Block until the next suspend event, or report `timedOut`. |
| `maxResults` | integer | 20 | Sessions reported, and threads reported per session. |

`waitForSuspendSeconds` is what makes a debugger usable through a request-response protocol.
Without it a caller has to poll and usually misses the moment; with it, "sleep until something stops" is one call that answers the moment a breakpoint is hit.

### `eclipse_debug_get_frames`

Read only. The stack of one suspended thread and the variables of one frame: index, declaring type, method with its argument types, line, source file, native flag, and per variable the name, declared type, value, `hasChildren` and the runtime type when it differs from the declared one.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `sessionId` | string | the only live session | Refused with the list when several are live. |
| `thread` | string | the single suspended thread | Refused with the suspended threads when several are stopped and none is named. |
| `frame` | integer | 0 | Stack frame index, top first. |
| `variablePath` | string | | Dotted path into the object graph, e.g. `this.buffer.count`. Numeric segments address array elements. |
| `maxResults` | integer | 100 | Frames reported, and variables reported for the selected frame. |
| `maxValueLength` | integer | 500 | Cut every rendered value at this length; `valueTruncated` says so. |

**One level per call.**
`variablePath` descends exactly as far as it is asked to and returns the children of the variable it reached, never further.
An unbounded walk over an object graph, a hundred element array or a live IDE object is how a client gets drowned, so it does not happen: go deeper with another call.

### `eclipse_debug_evaluate`

**Runs code inside the debugged program**, with every side effect that implies: field writes, method calls, IO.
Evaluates a Java expression against one stack frame of a suspended thread, through the same AST evaluation engine the Display view uses.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `expression` | string, required | | The expression to evaluate. |
| `sessionId` | string | the only live session | |
| `thread` | string | the single suspended thread | |
| `frame` | integer | 0 | Frame the expression sees as its context. |
| `timeoutSeconds` | integer, 1 to 20 | 10 | Reports `timedOut` rather than blocking past the call timeout; the evaluation may continue regardless. |
| `maxValueLength` | integer | 500 | |

Evaluation is asynchronous underneath, so the tool waits on a latch and answers with whatever arrived.
Compilation problems come back in `problems` rather than as a tool error: a mistyped expression is the caller's next question, not a broken server.
A runtime exception thrown by the evaluation is reported as `exception`.

### `eclipse_debug_control`

**Changes the state of the debugged program**, and `terminate` kills the process.
Takes `action`: `resume`, `stepOver`, `stepInto`, `stepReturn`, `suspend`, `terminate` or `disconnect`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `action` | string, required | | One of the seven above. An unknown action is refused with the list. |
| `sessionId` | string | the only live session | |
| `thread` | string | the single suspended thread | The stepping actions need one; ambiguous is refused with the candidates. |
| `waitForSuspendSeconds` | integer, 0 to 25 | 20 for resume and steps, 0 otherwise | How long to wait for the next suspend before answering. |

After a step or a resume the tool waits for the next suspend and reports the new location in the same answer, so stepping costs one call rather than two.
`timedOut` after a resume normally just means the program kept running, and the answer says that.

### `eclipse_list_declarations`

Enumerates the types, methods or fields a project declares in its own source, and cross-checks each against the places an Eclipse runtime instantiates a class by name.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeNames` | array of strings | | Report only these types, resolved directly instead of by walking a project. |
| `project` | string | | Project to enumerate. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `kinds` | array of `types` \| `methods` \| `fields` | `["types"]` | |
| `visibility` | array of `public` \| `protected` \| `package` \| `private` | all | |
| `status` | `dead` \| `live-via-registry` \| `undecidable` \| `all` | `all` | Report only this verdict. |
| `includeTest` | boolean | `false` | Include source folders the build path marks as test. |
| `includeReflection` | boolean | `true` | Scan source for `Class.forName` and `loadClass`. |
| `maxResults` | integer, 1 to 5000 | 500 | |

`typeNames` turns it around: when you already have candidates, it resolves each one and gives you the registry and API verdict per type without walking anything, sharing one registry index across the batch. Names that do not resolve to a source type come back in `unresolved`.

This is the candidate generation step of a dead code sweep. `eclipse_find_references` is the confirm step, and neither replaces the other: search is fast and resolves overloads and inheritance, but it cannot enumerate, and zero references does not mean dead.

Binary types are never listed. Only source package fragment roots are walked, so a class that exists a dozen times over inside built jars appears once, as source, rather than being deduplicated afterwards.

**`registryStatus` is three-valued, and the distinction is the point.**

- `dead` means only that no registry position this tool understands names it. It never means deleting it is safe.
- `live-via-registry` means not provably dead. It does not mean anything still uses it: an extension can be contributed to a point nobody reads any more, in a bundle that ships in no feature.
- `undecidable` means something names it in a position that cannot be judged.

A boolean verdict would report the undecidable cases as dead, which is the one failure mode that makes a tool like this dangerous rather than merely incomplete.

**Extension attributes are resolved through the schema, not grepped.** The rule is positional: a class name in a comment, a changelog or a `.txt` file keeps nothing alive. An element is resolved to its extension point, the point's `.exsd` says which of its attributes are java-typed, and only those count:

```xml
<attribute name="class" type="string" use="required">
  <appInfo>
    <meta.attribute kind="java" basedOn="org.eclipse.core.resources.filtermatchers.AbstractFileInfoMatcher:"/>
  </appInfo>
</attribute>
```

`basedOn` is verified and reported, but it **never demotes a verdict**. It is a single-valued hint that several real schemas cannot express: `org.eclipse.ui.decorators` names `ILabelDecorator`, while every decorator declared `lightweight="true"` implements `ILightweightLabelDecorator` instead. The schema is not lying, it is incapable of saying what it means. So `basedOnSatisfied` is a flag for a person to read, not an input to the status: unverifiable is not refuted, and unsatisfied is not refuted either.

It is `null` in two cases: the supertype is not resolvable in that project at all, and the named class is an `IExecutableExtensionFactory`, where `class="a.b.Factory:product"` means `basedOn` describes what the factory produces rather than the factory itself.

A class satisfies a `basedOn` that names the class itself.

**`apiTier` says what a workspace search can prove**, and it qualifies every verdict rather than replacing it. For an OSGi bundle the declaring package's export decides whether consumers can exist where you cannot see them:

| `apiTier` | Meaning | `searchIsAuthoritative` |
|---|---|---|
| `not-exported` | nothing outside the bundle may reference it | `true` |
| `internal-api` | exported `x-internal`, no legitimate outside consumer | `true` |
| `internal-api-friends` | exported `x-friends`, and the list is enumerable | `true` when every friend is a project here |
| `public-api` | consumers may exist anywhere | `false` |

`x-internal` counts as authoritative for the same reason `not-exported` does, and it is the stronger declaration of the two internal tiers: it says *no* bundle should use the package, where `x-friends` names some that may. Both rest on an access rule JDT and PDE check when consumers compile, not on anything OSGi enforces at runtime, and the answer's caveats say so rather than the field claiming more than it has.

`dead` on a `public-api` type proves nothing at all: `org.eclipse.ui.ide.IGotoMarker` has no workspace references and is implemented across the ecosystem. `dead` where `searchIsAuthoritative` is `true` has nowhere left to hide, and for `x-friends` that is exact rather than a heuristic, because the friend list names every bundle allowed to reference the package.

`apiRestrictions` reports the PDE API Tools javadoc tags on a type (`noreference`, `noextend`, `noimplement`, `noinstantiate`, `nooverride`). A type in a public package tagged `@noreference` is documented as not for consumption, so no references means more there than it does for untagged public API. The tags are read from the source; no API baseline is involved, since comparing against a baseline answers the different question of whether removing something breaks anyone.

**`apiTier` says what a workspace search can prove**, and it qualifies every verdict rather than replacing it. For an OSGi bundle the declaring package's export decides whether consumers can exist where you cannot see them:

| `apiTier` | Meaning | `searchIsAuthoritative` |
|---|---|---|
| `not-exported` | nothing outside the bundle may reference it | `true` |
| `internal-api` | exported `x-internal`, no legitimate outside consumer | `true` |
| `internal-api-friends` | exported `x-friends`, and the list is enumerable | `true` when every friend is a project here |
| `public-api` | consumers may exist anywhere | `false` |

`x-internal` counts as authoritative for the same reason `not-exported` does, and it is the stronger declaration of the two internal tiers: it says *no* bundle should use the package, where `x-friends` names some that may. Both rest on an access rule JDT and PDE check when consumers compile, not on anything OSGi enforces at runtime, and the answer's caveats say so rather than the field claiming more than it has.

`dead` on a `public-api` type proves nothing at all: `org.eclipse.ui.ide.IGotoMarker` has no workspace references and is implemented across the ecosystem. `dead` where `searchIsAuthoritative` is `true` has nowhere left to hide, and for `x-friends` that is exact rather than a heuristic, because the friend list names every bundle allowed to reference the package.

`apiRestrictions` reports the PDE API Tools javadoc tags on a type (`noreference`, `noextend`, `noimplement`, `noinstantiate`, `nooverride`). A type in a public package tagged `@noreference` is documented as not for consumption, so no references means more there than it does for untagged public API. The tags are read from source; no API baseline is involved, since comparing against a baseline answers the different question of whether removing something breaks anyone.

`typeTests` is reported separately from `registryEvidence` and never changes a verdict. A class named only by `<instanceof value="..."/>` in an enablement expression is `dead` by the rule above, because a type test is not instantiation, but deleting it breaks the expression *silently*: it stops matching rather than failing to compile, which is worse than an error.

The other positions read are declarative services (`implementation@class`, `provide@interface`, and the lifecycle and binding method names), `Bundle-Activator`, `META-INF/services` (the file name is the interface, each line a provider), and reflective loads whose argument is a single string literal.

A name built at runtime is not resolvable by any static analysis, so those sites are reported in `dynamicReflectionSites` and every `dead` verdict in that project is flagged provisional rather than silently downgraded.

Members of a live type are `undecidable`, not dead: the framework holds the instance and calls whatever its contract says, and no declaration list can see that.

Extension points these projects contribute to but that are declared outside the workspace have no readable schema. Class-looking attribute values under those points come back `undecidable`, and the points are listed in `extensionPointsWithoutSchema`, scoped to the projects that were asked about rather than to the whole workspace.

### `eclipse_get_call_hierarchy`

Returns the callers of a Java method, to the requested depth.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type declaring the method. |
| `methodName` | string, required | | All overloads are followed. |
| `project` | string | whole workspace | |
| `direction` | `callers` \| `callees` \| `both` | `callers` | Only `callers` is implemented, see below. |
| `depth` | integer, 1 to 5 | 2 | Each level costs another search. |
| `maxResults` | integer, 1 to 2000 | 200 | Bounds the whole tree, not each level. |

For dead code the useful question is not whether something is referenced but whether it is reachable from anything that is itself reachable, and `eclipse_find_references` cannot answer that: a method whose only callers are themselves uncalled is still dead.

Callers already in the tree are not expanded again, so mutual recursion terminates instead of looping.

`callees` is **not implemented** and says so rather than returning an empty answer. Callers come from the search index; callees would need the AST of every method body, which is a different and far more expensive traversal.

### `eclipse_get_type_hierarchy`

Returns the supertypes and subtypes of a Java type as known to JDT, including types from the classpath.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `project` | string | whole workspace | Project used to resolve the type and to scope the hierarchy. |
| `direction` | `supertypes` \| `subtypes` \| `both` | `both` | Only the requested direction is computed, the subtype direction being the expensive one. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"type":"org.eclipse.jface.viewers.TreeViewer",
 "supertypes":["org.eclipse.jface.viewers.AbstractTreeViewer"],
 "subtypes":["org.eclipse.jface.viewers.CheckboxTreeViewer"],
 "truncated":false}
```

### `eclipse_get_source`

Returns the Java source and Javadoc of a type or of its members, resolved through the project classpath.
Works for types in libraries as well, as long as a source attachment exists, which is the part a shell cannot do.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `memberName` | string | the whole type | Method or field name. All overloads are returned. |
| `project` | string | whole workspace | Project used to resolve the type. |
| `maxLength` | integer, 100 to 200000 | 40000 | Maximum characters per returned element. |

```json
{"type":"org.eclipse.jface.viewers.TreeViewer","binary":true,
 "path":"/home/user/.p2/.../org.eclipse.jface_3.35.0.jar","sourceAvailable":true,
 "elements":[{"element":"org.eclipse.jface.viewers.TreeViewer.setInput(Object)",
              "line":812,"source":"/** ... */\npublic void setInput(Object input) { ... }",
              "truncated":false}]}
```

When no source is attached, `sourceAvailable` is `false` and a `hint` explains why.

### `eclipse_search_types`

Finds Java types by name across the workspace and everything on the project classpaths, jars included.
Use it to turn a simple name into a fully qualified one.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `pattern` | string, required | | Simple or qualified name, case insensitive, `*` and `?` allowed. |
| `project` | string | whole workspace | Project whose classpath is searched. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"total":2,"truncated":false,"types":[
  {"fullyQualifiedName":"org.eclipse.jface.viewers.TreeViewer","simpleName":"TreeViewer",
   "packageName":"org.eclipse.jface.viewers","path":"/.../org.eclipse.jface_3.35.0.jar","binary":true}]}
```

### `eclipse_delete`

**Deletes a source file from the workspace.** Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string | | Fully qualified name of the type. Without `memberName`, its whole file is deleted. |
| `typeNames` | array of strings | | Delete several types in one call. |
| `memberName` | string | | A field, method or nested type to delete instead of the file. |
| `project` | string | every Java project | Project to resolve the name in. |
| `dryRun` | boolean | `true` | |
| `force` | boolean | `false` | Delete despite references, a registry position, or a public API package. |

The last step of a dead code sweep, after `eclipse_list_declarations` finds candidates and `eclipse_find_references` confirms them. It reports `references`, `registryEvidence` and `apiTier` on every call, and **refuses unless `force`** when any of the three says the type is still wanted: references remaining make a deletion a compile break rather than a cleanup, a registry position fails at runtime instead of at compile time, and a public API package can have consumers no search here can see.

A file declaring more than one top level type is refused outright when deleting a file.

`typeNames` deletes a batch, building the registry index **once** rather than per type, which matters because that index walks every project in the workspace. Each type is reported separately with its own refusal, so one that cannot be deleted does not stop the others.

**With `memberName` it deletes one member instead**, which is about half the edits of a real sweep: dead constants, private fields, unused methods, nested types. It goes through `IMember.delete`, whose source range includes the javadoc, so the comment goes with the declaration rather than being left behind describing something that no longer exists. An overloaded method name is refused, since the tool cannot tell which one you mean. References in the *same file* count here, unlike a file delete: the file keeps compiling around the hole.

**Read this limitation.** The deletion goes through LTK as a *resource* delete, and PDE's manifest participants are enabled on `IType` and `IPackageFragment` rather than on `IResource`, so **`plugin.xml` class attributes and `Export-Package` are not updated.** Whatever `registryEvidence` the answer reports is what will be left naming a class that no longer exists, and `danglingAfterDelete` says so on any call that would go through. The reason it works this way is that JDT's own delete refactoring, the one PDE's participants are written for, has no usable public API: `DeleteDescriptor` carries no setters and its processor is internal to `org.eclipse.jdt.ui`.

### `eclipse_rename`

**Modifies source files, and a rename can touch hundreds.**
Renames a Java type, method, field, package or compilation unit through the JDT refactoring engine.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type. With `memberName`, the type declaring it. |
| `memberName` | string | the type itself | Method or field to rename. |
| `newName` | string, required | | The new simple name. |
| `project` | string | whole workspace | |
| `kind` | `auto` \| `type` \| `method` \| `field` \| `package` \| `compilationUnit` | `auto` | |
| `updateReferences` | boolean | `true` | |
| `updateQualifiedNames` | boolean | `false` | Also update qualified names in non-Java files, matched textually. |
| `renameGettersAndSetters` | boolean | `false` | For a field. |
| `dryRun` | boolean | `true` | |

```json
{"element":"sample.Target","newName":"Renamed","refactoring":"org.eclipse.jdt.ui.rename.type",
 "dryRun":true,"warnings":[],"affectedFileCount":2,
 "affectedFiles":["/app/src/sample/Target.java","/app/src/sample/User.java"],"applied":false}
```

Going through the refactoring engine rather than editing text is the whole point: overrides and implementations follow, and the refactoring participants that update non-Java references, such as `plugin.xml` class attributes, fire.

Preconditions are checked before anything is written. A rename that would collide with an existing type, or produce an invalid Java name, is **refused** with the reason rather than half applied. Warnings that do not block are reported in `warnings`.

An overloaded method is refused rather than guessed at, because a rename has to name exactly one member.

### `eclipse_clean_up`

**Modifies source files.** Runs as a dry run unless `dryRun` is `false`. Takes `cleanUps` (required), `path` or `project`, and `maxResults`.

Applies JDT's own clean-ups, the transformations behind *Source > Clean Up*, so the result is what Eclipse itself produces rather than a rewrite of our own. Currently offered, by their JDT option key:

| Key | Does |
|---|---|
| `cleanup.use_lambda` | anonymous class to lambda |
| `cleanup.instanceof` | pattern matching for `instanceof` |
| `cleanup.convert_to_enhanced_for_loop` | index loop to enhanced `for` |
| `cleanup.remove_unused_imports` | remove unused imports |
| `cleanup.make_variable_declarations_final` | add missing `final` |
| `cleanup.stringbuffer_to_stringbuilder` | `StringBuffer` to `StringBuilder` |

An unknown key is refused with the list. Each clean-up is a semantic transformation with conditions, so **a file reported with no edits is one where the pattern did not apply, not a failure.**

This is the one place in the server that takes a **discouraged dependency**: `CleanUpConstants` and the `*CleanUpCore` classes are `x-friends` to `org.eclipse.jdt.ui`, and JDT-LS is not on that list either despite using them. It was a deliberate decision rather than an oversight, because there is no public alternative and reimplementing JDT's transformations is not one. It can break in any JDT release with no compile-time signal. `CleanUpRefactoring` was rejected as the entry point because it lives in `org.eclipse.jdt.ui` and would pull the UI in.

`eclipse_remove_unused_imports` stays as it is, on public API, since a targeted tool with no such dependency is worth keeping.

### `eclipse_remove_unused_imports`

**Modifies source files.** Runs as a dry run unless `dryRun` is `false`. Takes `path` for one file or `project` for every file in one, plus `build` (`true`) and `maxResults`.

Removes the imports the compiler reports as unused, and nothing else. `eclipse_organize_imports` also sorts and regroups, so on a file where one import is dead it rewrites the whole block and the change hides among lines nobody meant to touch.

**The compiler decides what is unused**, which is what makes this safe: the project's settings govern, including whether a reference from javadoc keeps an import alive. A remover that reasons about code alone deletes those and leaves `Javadoc: X cannot be resolved` behind, which is exactly what happened to a client that wrote its own.

Markers are only as current as the last build, so it builds first unless told not to. Deleting an import flagged before your last edit would remove one that is now in use.

It exists because JDT's own clean-up machinery is not reachable: `CleanUpConstants`, which holds `REMOVE_UNUSED_CODE_IMPORTS`, is in `org.eclipse.jdt.internal.corext.fix`, x-friends to `org.eclipse.jdt.ui`. Problem markers plus `IImportDeclaration.delete` are public API and give a smaller diff than the clean-up would.

### `eclipse_organize_imports`

**Modifies the file.**
Organizes imports the way JDT does, with the project's own import order and on-demand thresholds, and saves.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the Java file, e.g. `/app/src/com/example/Main.java`. |
| `resolveAmbiguous` | boolean | `false` | Take the first candidate when a simple name matches several types. |

```json
{"path":"/app/src/com/example/Main.java","importsAdded":2,"importsRemoved":1,
 "changed":true,"ambiguous":[]}
```

By default an ambiguous name, `List` matching both `java.util` and `java.awt` say, aborts the operation with an error naming the candidates rather than guessing, and the file is left untouched.

### `eclipse_format`

**Modifies the file.**
Formats a Java file with the formatter settings of its own project and saves it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the Java file. |

```json
{"path":"/app/src/com/example/Main.java","changed":true}
```

### `eclipse_read_file`

Reads a workspace file by workspace path, the same path form `eclipse_open` takes. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file. |
| `offset` | integer | 1 | First line to return, 1 based. |
| `limit` | integer | rest of file | How many lines to return. |
| `maxBytes` | integer | 1000000 | Refuse rather than return more than this. |
| `refresh` | boolean | `true` | Read outside changes into the workspace first. |

This exists because a client is not always on the same machine as the IDE, and once the window is hidden there is no filesystem to fall back on. It is also the only way to look at the `plugin.xml` or `.exsd` that `eclipse_list_declarations` cites as evidence, in the same IDE the verdict came from rather than in your own copy of the tree, which may not even be the same revision.

The file is read through the workspace, so it uses the encoding Eclipse has for it. A naive read of the bytes gets that wrong silently for properties files and anything not UTF-8.

Binary files are reported as `binary` rather than returned as a mangled string.

### `eclipse_write_file`

**Writes a text file at a workspace path.**
The counterpart of `eclipse_read_file`, and there for the same reason: a client is not always on the same machine as the IDE, so writing through its own shell is not always possible.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file. The first segment is a project that has to exist. |
| `content` | string, required | | The complete text, or the text to add when `append` is true. |
| `overwrite` | boolean | `false` | Replace the content of a file that already exists. |
| `append` | boolean | `false` | Add to the end instead of replacing. Creates the file when it does not exist. |
| `createParents` | boolean | `true` | Create the folders leading to the file. The project itself is never created. |
| `charset` | string | the file's | Encoding to write with. On a new file an explicit charset is recorded on the resource. |
| `dryRun` | boolean | `false` | Report what would be written without writing it. |

```json
{"path":"/app/src/com/example/Main.java","created":true,"appended":false,
 "charset":"UTF-8","bytes":214,"createdFolders":["/app/src/com","/app/src/com/example"],"written":true}
```

Writing through the workspace rather than onto the disk is the point: the file gets the charset Eclipse has for it, the resource tree sees the change at once instead of at the next refresh, and the previous content goes into the local history, where *Compare With > Local History* recovers it.

An existing file is refused unless `overwrite` is true, so a path that was meant to be new does not quietly replace something.
An explicit `charset` is also written to the resource, because a file encoded as anything but its container's default decodes wrongly the next time something reads it.

A file open in a dirty editor is written underneath that editor. Eclipse notices and marks the editor as out of date rather than losing anything, but the unsaved buffer still wins if the user then saves.

For Java, follow the write with `eclipse_format`, so the result matches the project's conventions rather than the model's.

### `eclipse_edit_file`

**Replaces one passage of a workspace file with another.**
This is how a file is changed without resending it: `eclipse_write_file` takes the complete content, so changing one line of an 800 line file through it means reading and returning all 800.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file. |
| `oldText` | string, required | | The exact text to replace, whitespace included. |
| `newText` | string, required | | What to put in its place. Empty deletes the passage. |
| `replaceAll` | boolean | `false` | Replace every occurrence instead of refusing when there is more than one. |
| `dryRun` | boolean | `false` | Report the match count and the context without writing. |

The edit is checked against what the caller believes is there.
`oldText` that matches nothing is refused, and `oldText` that matches more than once is refused with the count unless `replaceAll` is set, so an edit made on a stale reading of the file fails instead of landing somewhere unintended.
Give enough surrounding text to be unique rather than a bare identifier.

Writing goes through the workspace, so the file keeps its charset, the resource tree sees the change at once, and the previous content goes into the local history where *Compare With > Local History* recovers it.
The answer shows the changed lines with context, so it reports the result rather than promising it.

**Line endings need no attention.**
A file written with CRLF, which is what a repository checked out on Windows looks like, is matched against an `oldText` given with LF: without that every edit spanning a line break in such a file was refused as if the caller had read a different file.
The file keeps its own delimiter, so the edit is not what converts it, and the answer carries `lineDelimiter` and `matchedAfterConvertingLineEndings` when that is what happened.

For Java, follow the edit with `eclipse_format`.

### `eclipse_search_text`

Searches the text of workspace files, including the ones the Java model cannot see: `plugin.xml`, `.exsd`, `.project`, manifests, properties. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `pattern` | string, required | | Text, or a regular expression when `isRegex`. |
| `isRegex` | boolean | `false` | |
| `isCaseSensitive` | boolean | `false` | |
| `projects` | array of strings | whole workspace | |
| `path` | string | | Restrict to a workspace folder or file. |
| `fileNamePattern` | string | every file | Glob over file names, e.g. `*.exsd`. |
| `excludePathPattern` | string | | Glob over the workspace path; matches are skipped. |
| `includeDerived` | boolean | `false` | Include resources Eclipse marks derived. |
| `maxResults` | integer, 1 to 5000 | 200 | |

For Java elements `eclipse_find_references` answers better, because it resolves overloads and inheritance and this does not. This is for everything that is not Java.

**Maven and Gradle output is not marked derived**, so `includeDerived` does not exclude it and a search of a built tree comes back mostly build output. `excludePathPattern` is the answer, for example `*/target/*`, and `excludedByPath` reports how many matches it dropped.

It runs through Eclipse's own `TextSearchEngine`, so **resources Eclipse marks derived are excluded by default**. That is the difference between this and a raw grep of the same tree, where every type comes back once per copy under a build output directory.

Each match reports the file, the line number and the line, capped at 500 characters.

**One file on disk counts once.** In a platform workspace almost every project is nested inside another, so a single file is reachable through several workspace paths and the same match arrives once per path. Matches are deduplicated by physical location and offset, the other paths come back as `alsoVisibleAs`, and `duplicatePathsCollapsed` says how many were folded away. Without this a count is inflated by an unpredictable factor that a client cannot detect without a filesystem, which is the thing this tool exists to do without.

### `eclipse_find_resources`

Finds files by **name** across three places no other tool here covers together: the workspace, the bundles of the active target platform, and the bundles of the running installation. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `namePattern` | string | | One name or glob to look for. |
| `namePatterns` | array of string | | Several at once, which is how an inventory is taken. |
| `extensions` | array of string | | Restrict to these file extensions. |
| `scope` | string | `all` | `workspace`, `target`, `installation` or `all`. |
| `bundleFilter` | string | | Only bundles whose symbolic name matches. |
| `compact` | boolean | `false` | One line per hit instead of the full record. |
| `countOnly` | boolean | `false` | Just the counts. |
| `copyTo` | string | | Extract the hits into this directory. |
| `dedupe` | boolean | `false` | Collapse the same file found through several scopes. |
| `includeDerived` | boolean | `false` | Include derived workspace resources. |
| `maxResults` | integer | 100 | |

`eclipse_search_text` searches the **content** of workspace files, so an icon inside a jarred bundle is invisible to it.
That is the gap this closes: "does an SVG of this name already exist anywhere in Eclipse" is answerable only by looking inside jars.

Each hit says which of the three scopes it came from, the bundle and version that holds it, the path inside that bundle, and a location the bytes can be read from.
`copyTo` extracts the hits, which is what makes a found icon usable rather than only known about.

For an inventory, ask several names at once with `namePatterns` and cut the answer down with `compact` or `countOnly`, rather than one call per name.
Use `scope` `installation` for that, because `all` returns the same picture three or four times over when the repositories are also in the workspace.

A name match is not a match in meaning.
An icon called `remove` upstream may be a red cross where yours is a minus, so look at what you found before replacing anything with it.

### `eclipse_list_editors` and `eclipse_close_editor`

`eclipse_list_editors` lists the open editors in tab order, with the file each shows, which is active, which are pinned, and which have unsaved changes. Read-only, no arguments.

It is the tool that answers "is there unsaved work", which `eclipse_restart` refuses on and which nothing else reported on its own. It matters more once the IDE can be hidden, since nobody can look at a window they cannot see.

`eclipse_close_editor` **changes what the IDE shows**. It selects by `path`, by `title` substring, or `all`, and refuses to act on omission.

A clean editor closes with no ceremony, because closing it loses nothing. **A dirty editor is refused** unless `save` is passed, which saves it first, or `discardUnsaved`, which throws the changes away and is never a default. The save goes through the editor rather than through `closeEditors(refs, true)`, so no save prompt is ever raised: an unattended call cannot leave a dialog waiting for somebody who is not there.

### `eclipse_get_project_dependencies`

Reports the projects a project references and the open projects that reference it, as Eclipse resolves them.
This covers JDT build path project entries and the dynamic references PDE computes from `Require-Bundle`, so it answers what `.project` and `.classpath` cannot by inspection.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string | every open project | |
| `direction` | `references` \| `referencedBy` \| `both` | `both` | |
| `transitive` | boolean | `false` | Follow the graph instead of direct neighbours only. |
| `maxResults` | integer, 1 to 2000 | 200 | |

`referencedBy` only ever reports open projects, because that is all `IProject.getReferencingProjects()` sees, and it is also all the builder sees. Use it before closing a project, and to find the leaves of a graph.

### `eclipse_get_classpath`

Reports the build path of a Java project as JDT resolved it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string, required | | |
| `resolved` | boolean | `true` | Expand containers and variables. |
| `maxResults` | integer, 1 to 5000 | 500 | |

`rawEntries` mirrors `.classpath`, but each container also carries its description and, for the JRE container, `boundJre` with the name, type and install location of the JDK actually bound to it.
That binding is the point of the tool. A `JavaSE-1.8` container says nothing about which JDK the IDE chose for it, and that choice decides whether a `--release` compile works. Nothing outside the IDE knows it.

`resolvedEntries` is the expansion: the jars behind each container, source attachments, access rules and classpath attributes.

### `eclipse_open`

**Changes what the IDE shows**, writes nothing.
Opens a workspace file in an editor and optionally reveals a line, so the person at the IDE is looking at what you are talking about instead of copying a path.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file, or an absolute path on disk for a file outside the workspace. |
| `line` | integer | | Line to reveal, 1 based. |
| `activate` | boolean | `true` | Bring the editor to the front. |

`revealedLine` reports the line actually revealed, which is clamped to the end of the file.
A path is tried as a workspace path first, then as a location a project contains, and only then as a file outside the workspace, which opens the way File > Open File does, through a file store in the default editor for the name; `external` in the answer says which it was.
A `.target` file in a fixtures directory that is no project is the case this exists for.

### `eclipse_open_compare`

**Changes what the IDE shows**, writes nothing.
Opens Eclipse's compare editor on a workspace file, against another file, against content you supply, or against a Git revision.
With the preference `org.eclipse.compare.UnifiedDiff` set, the file opens in its own editor instead and the difference is drawn into it as line header code minings and annotations; `unifiedDiff` in the answer says which presentation the call went to.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `left` | string, required | | Workspace path of the file to compare. |
| `right` | string | | Workspace path to compare it against. |
| `content` | string | | Text to compare it against. |
| `revision` | string | | Git revision to compare it against, such as `HEAD`, `HEAD~1`, a branch, a tag or a commit id. |
| `leftLabel` | string | the workspace path | Label over the left side. |
| `rightLabel` | string | what the right side is | Label over the right side. |
| `activate` | boolean | `true` | Bring the compare editor to the front. |

Exactly one of `right`, `content` and `revision` is required.

`content` is the interesting one: it shows a proposed edit side by side with the file, with syntax colouring and the structural Java compare, before anything is written.
A diff pasted into a chat window is the same information in a form nobody reviews carefully.

Both sides are read-only. The editor is a view of a difference, not a merge tool, so no path through this tool can modify a file.

`identical` reports that the two sides are byte for byte the same.
The editor still opens and shows nothing, which is confusing enough to be worth saying in the answer rather than leaving the caller to wonder.
The compare input is also always built as a real difference node for the same reason the p2 tools install their own trust callback: an empty result makes the compare framework raise a modal "no differences" dialog, and a dialog on a path a client drives is a hang nobody is there to clear.

`revision` needs the `org.eclipse.jgit` bundle, which is an **optional** dependency: every Eclipse with EGit has it, a bare Platform SDK does not, and where it is missing this one argument is refused with an explanation while everything else keeps working.
The repository is found by walking up from the file, so it does not matter whether the project is shared in the IDE.

### `eclipse_get_editor_context`

Returns the file in the active editor, the cursor position and the current selection.
Use it to resolve vague references such as "this method" or "the file I am looking at".
No arguments.

```json
{"hasActiveEditor":true,"title":"Main.java","path":"/app/src/com/example/Main.java",
 "project":"app","dirty":false,"cursorLine":42,"cursorOffset":1187,
 "selectionLength":12,"selectedText":"doSomething()","selectedTextTruncated":false}
```

`openEditors` lists every open editor with its `dirty` flag. A file read from disk while its editor is dirty is not the file the user is looking at, and nothing outside the IDE can tell, so this is worth checking before drawing conclusions from file contents.

`selectedText` is capped at 2000 characters.
When there is no workbench, no window or no file-backed editor, the answer is `{"hasActiveEditor":false}`.

### `eclipse_start_sampling` and `eclipse_stop_sampling`

Samples thread stacks at a fixed interval, to profile an operation or diagnose a freeze.

`eclipse_start_sampling` takes `threads` (`ui` or `all`), `threadNames`, `intervalMillis` (100), `maxSamples` (300) and `maxDepth` (80), and returns a `sessionId`.
`eclipse_stop_sampling` takes that id, plus `topMethods`, `minSamples`, `includeRawSamples`, `keepRunning` and `frameFilter`.

`frameFilter` restricts the aggregate to stacks containing a package prefix or a class, and is applied when reading rather than when sampling, so one session can be read from several angles with `keepRunning`. It earns its place because the top of an unfiltered IDE profile is Jetty accept loops, the AWT event pump and the reference handler, none of which is ever the answer to the question being asked.

**Turn `includeIdleThreads` on to diagnose a freeze.** A frozen thread is usually parked, so the default, which exists to stop the pooled threads of an idle IDE dominating the result, drops exactly the samples that explain a stall. Profiling slow work and profiling a freeze want opposite settings.

Sampling runs on a daemon thread through `ThreadMXBean`, which needs neither the UI thread nor any workspace lock, so it keeps working while the IDE is frozen. That is the requirement, not a detail: a profiler that queues behind the freeze is useless for the case it exists for.

The result is **aggregated, not dumped**: the frames where time was actually spent (`topBySelfTime`), the frames most often on the stack (`topByPresence`), and the samples merged into one call tree. A hundred samples of seventy frames is seven thousand lines, so the raw samples only come back on request.
Frames on every sample are listed once under `onEveryStack` instead of heading `topByPresence`, a run of frames with the same count is folded into `chain`, and samples deeper than `maxDepth` are rooted at the outermost frame they still share, so one path through the event loop is one root.

`ThreadMXBean` sampling is safepoint biased, so tight loops without safepoint polls are under-represented. Treat it as "where is the time going", not as an exact profiler.

### `eclipse_start_flight_recording` and `eclipse_stop_flight_recording`

Records this IDE's JVM with Java Flight Recorder and answers **where the memory goes**.

`eclipse_start_sampling` cannot answer that. It samples call stacks by time, so code that allocates heavily and computes little is invisible to it, which is most of what causes a rising heap.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `settings` | `default` \| `profile` | `profile` | `profile` adds the allocation and execution samples. `default` costs about one percent and covers GC and threads. |
| `durationSeconds` | integer | 1800 | Stops on its own. `0` runs until stopped, which then has to happen. |
| `maxAgeSeconds` | integer | 600 | History kept, so a problem that appears after hours can still be dumped when it does. |
| `maxSizeMegabytes` | integer | 100 | |
| `name` | string | | Label, shown in JDK Mission Control. |

Stopping dumps the recording and returns it aggregated: `allocationByClass`, `allocationByStack`, `hotMethods`, `gc` and the event counts. `keepRunning` reads a recording without ending it, so one recording can be read from several angles with different `frameFilter` values. `outputPath` keeps the `.jfr` file for opening in JDK Mission Control; without it the file is deleted after reading.

**`allocationByStack` is the field that names a culprit.** A class on its own rarely does: a heap dominated by `Path` and `byte[]` says nothing until the call chain shows which code asked for them. `stackDepth` decides how much of the chain is aggregated, so deeper separates callers that share a top frame and shallower merges them.

**Read the bytes as an estimate.** The allocation sampler is throttled and reports a weight per sample, so the figures rank allocators correctly and do not add up to what the process allocated.

Recording happens in-process through `jdk.jfr`, so nothing has to be installed and no JVM flag is needed at startup. The packages are imported optionally: a JVM that does not expose them gets a refusal saying so rather than a failure. An Eclipse started from a normal `eclipse.ini` has them, because it launches with `--add-modules=ALL-SYSTEM`.

### `eclipse_start_screencast` and `eclipse_stop_screencast`

Records a shell or a part as PNG frames and assembles them into an animated GIF.
**Writes files** to a directory of its own and changes nothing in the IDE.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `target` | `part` \| `shell` | `shell` | Inferred from `part` when that is given. `eclipse_start_screencast` only. |
| `part` | string | | Part id, from `eclipse_list_ui_targets`. It has to be visible. |
| `shellTitle` | string | | Title of the shell, or a substring. Omit for the active shell. |
| `intervalMillis` | integer, 100 to 10000 | 500 | Time between frames, on top of the paint itself. |
| `maxFrames` | integer, 1 to 1000 | 120 | Recording stops on its own after this many frames. |
| `maxWidth` | integer | 800 | Downscale each frame to this width. |
| `directory` | string | temp | Where the frames go. |
| `bounds` | string | | Record only this region, `x,y widthxheight` in points: in the shell's client area for a shell, relative to the part for a part. |
| `parts` | array of string | | For a shell recording, only the union of these parts' bounds, so the recording covers the editor area alone. |
| `caption` | string | | Text drawn on every frame of the segment. |
| `captionPosition` | `over` \| `above` \| `below` | `over` | `over` is translucent over the bottom of the picture; `above` and `below` add an opaque bar outside it. |
| `resume` | string | | Session id of a stopped screencast to continue as one more segment in the same directory, or `latest` for the most recently stopped one. |
| `gapMillis` | integer, 0 to 60000 | 1000 | With `resume`, how long the last frame before the gap is held. |
| `sessionId` | string | most recent | `eclipse_stop_screencast` only. |
| `gif` | boolean | `true` | Assemble the GIF; off leaves only the frames. |
| `loop` | boolean | `true` | Loop the GIF. |
| `outputPath` | string | `screencast.gif` in the frame directory | |
| `keepFrames` | boolean | `true` | Leave the PNG frames on disk. |

This is what shows a change in motion rather than before and after: a tab overflowing while a sash is dragged, a hover appearing, a view repainting after a theme switch.

Each frame is painted on the UI thread through `Control.print`, the path `eclipse_screenshot` falls back to on a compositing window manager, so it records what the IDE paints and not native popups, menus or dialogs, and a shell recording has no window decorations.
The paint is the only part on the UI thread; scaling and encoding run on a thread of the session's own.
2 to 5 frames a second is what a full shell sustains without slowing the IDE.

**A recording a person reads is several segments with captions.**
`resume` continues a stopped session: the target, interval and crop stay, `maxFrames` counts the new segment, `caption` replaces the previous one, and `eclipse_stop_screencast` assembles every segment into one GIF.
`resume: latest` names the most recently stopped session, because ids count up per IDE session and a static scenario that wrote `screencast-1` appended its retry to the first attempt.
A caption `over` the picture covers its bottom rows, which for a crop to an editor are the page tabs; `above` or `below` puts the bar outside the picture and adds its height to the frame.
Between segments the IDE can be photographed, rearranged or left alone; the wall clock time of the pause is not in the recording, the last frame before it is held for `gapMillis` instead.
`bounds` and `parts` crop every frame, `parts` as the union of the named parts' bounds in the shell's client area, which is the coordinate system `eclipse_get_widget_tree` reports as `boundsInShell`; a region entirely outside the target is refused up front.
`eclipse_pause` is the wait between steps.

On GTK 3 `Control.print` leaves the printed widget unpainted on screen until something invalidates it, so every print here queues a redraw of what it printed; before that a root capture taken during or after a recording was a blank editor, with nothing in the answer to say so.

Each frame stays in the GIF as long as it really did on screen, so a stall is visible as a held frame.
`averagePaintMillis` is what one frame cost the UI thread, which is added to the interval between frames: a full shell on a HiDPI monitor paints in about half a second, a single part far faster.
`lateTicks` and `maxLatenessMillis` report how long the timer waited past its due time, which is the recording's own measurement of how busy the IDE was with other things.
The GIF is written through SWT's own image loader onto a fixed 256 colour palette, which posterises gradients; the PNG frames stay lossless for `ffmpeg -framerate 4 -i frame-%04d.png out.mp4` through `eclipse_run_command`.

Recording stops on its own at `maxFrames`, when the target is disposed, or when a paint fails; `stoppedBy` says which.

### `eclipse_list_ui_targets`

Lists every open shell with its title, modality and bounds, and every workbench part with its id, title and visibility.
It is also the only way to answer "which dialog is open right now".

With `includeAvailableViews` it also lists the views registered in this IDE whether or not they are open, which is where `eclipse_show_view` gets its ids.
There are several hundred, so `filter` matches a substring of the id or the label and `maxResults` defaults to 100.

### `eclipse_list_commands`

Lists the workbench commands this IDE defines, which is where `eclipse_run_workbench_command` gets its ids.
Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `filter` | string | no filter | Substring of id, name or category, case insensitive. |
| `handledOnly` | boolean | `false` | Only commands whose handler would act right now. |
| `includeParameters` | boolean | `false` | Each parameter's id, name and optionality. |
| `maxResults` | integer, 1 to 500 | 100 | |

Each command reports its `id`, `name`, `category`, `description`, whether a handler is active (`handled`) and whether it is currently `enabled`.
Handled and enabled are evaluated against the workbench at the moment of the call, so they answer about now rather than about the registry in general.
`keybinding` carries the active binding formatted the way the menus show it, when there is one.

Pass a `filter`: an IDE defines around two thousand commands, so without one the answer is the first page of a very long list.
Commands that were defined and then undefined at runtime are skipped rather than failing the whole listing.

### `eclipse_run_workbench_command`

**Does whatever the command does.**
It executes a workbench command through Eclipse's command and handler framework, exactly as its menu entry, toolbar button or keybinding would.
The effect belongs to the command's handler: unknown to this server, unknowable in advance, and able to be anything the IDE can do.
This is the single biggest capability here, because most of what an IDE can do exists only as a command with no other API, and running one by id makes all of it reachable at once.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `command` | string, required | | Command id, or the label a person reads in the menu. Resolved by exact id, then exact name, then substring, and refused with the candidates when ambiguous. |
| `parameters` | object | | Parameter id to string value, for commands that take them. |
| `dryRun` | boolean | `false` | Resolve the command, report `handled`, `enabled` and its parameters, execute nothing. |
| `timeoutSeconds` | integer, 1 to 25 | 10 | Cap on waiting for the handler. |

The answer carries `executed`, `id`, `name`, `handled`, `enabled`, `elapsedMillis`, `handlerFinished` and the `outcome` verb, and, when the handler returned something, its `returnValue` capped at 500 characters.

`handlerFinished` and `outcome` come from an `IExecutionListener` registered on the resolved command for the duration of the call, the same source Eclipse's own usage monitoring hears.
They report what the framework observed rather than what the call returned: `outcome` is `success`, `failure` or `notHandled`, and only success and failure count as the handler having run.

**Not handled is an answer, not an error.**
Most commands are handled only while a particular part is active or a particular selection exists, so `handled: false` usually means the context is missing rather than that the command does not exist.
Activate the part with `eclipse_set_part_state` and try again.
`enabled: false` says the same thing one step later: a handler is active but its enablement currently says no.

**The dialog hazard is worth knowing before the first call.**
Many handlers open a modal dialog, and a dialog holds the UI thread inside the execute call until somebody answers it, which no timeout from outside can interrupt.
Whether a given handler opens one cannot be known in advance, so the hazard is handled at the timeout instead: the wait is capped by `timeoutSeconds`, running out returns `timedOut: true` with a note rather than an error, and points at `eclipse_list_ui_targets` to see the dialog and `eclipse_dismiss_dialog` to answer it.
Read together with the verdict fields, the timeout stops being ambiguous: `timedOut: true, handlerFinished: false` is a dialog still holding the handler inside `execute`, while `timedOut: true, handlerFinished: true` is a slow handler that already reached its verdict after the wait ran out.
If the command finishes after that answer has gone out, what it did is written to the Error Log rather than lost silently.

Two commands are refused outright, whatever the arguments: `org.eclipse.ui.file.exit`, because ending the IDE ends this server with it and nothing could undo that from the client side, and `org.eclipse.ui.file.restartWorkbench`, which `eclipse_restart` does in an orderly way.

### `eclipse_set_ide_visibility`

**Changes what the user sees**, in the one way they cannot undo from the IDE.
Takes the Eclipse window off the screen, or brings it back, for using the IDE as a backend rather than as something to look at.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `visible` | boolean, required | | `false` takes it off the screen, `true` brings it back and focuses it. |
| `mode` | `hidden` \| `minimized` | `hidden` | How to take it off the screen. Ignored when showing. |

The IDE keeps running while hidden. Builds, searches, tests and every other tool here work unchanged, because the workbench event loop belongs to the display and not to the window.

`hidden` removes the window from the screen and the taskbar entirely; `minimized` leaves it reachable by hand, which is the safer choice when a person is at the machine.

Hiding a window is easy to make unrecoverable, and that is the whole risk here: a hidden window has no menu and no taskbar entry, so the only way back is this tool. Two things make it safe. Calling it with `visible: true` restores it, and the plug-in restores every window it hid when it stops, so disabling or uninstalling the server cannot leave an IDE nobody can see and nothing can bring back.

While hidden, dialogs are still raised and are still invisible. `eclipse_list_ui_targets` and `eclipse_dismiss_dialog` remain the way to see and answer them, and they are worth more than usual in this state.

### `eclipse_set_model_visibility`

**Shows or hides an element of the e4 application model by its id**, which is what reaches the things no other tool here can: the status line and other trim bars, the main menu, and one window out of several.
Runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `elementId` | string, required | | Id of the model element. |
| `visible` | boolean | | Take the element out of the layout while the renderer keeps it. |
| `toBeRendered` | boolean | | Throw the widget away entirely. Refused for a window. |
| `dryRun` | boolean | `true` | Lists what the id matches. |
| `maxResults` | integer | 20 | |

**This is remembered.**
Unlike `eclipse_set_ide_visibility`, which only calls `Shell.setVisible` for the session, this writes the model, and the workbench saves the model on exit, so an element hidden here is still hidden after a restart and there is no menu entry to undo it with.

Two flags exist and they are not the same.
`visible` takes the element out of the layout while the renderer keeps it, and `toBeRendered` throws the widget away entirely, which is harder to reason about and is refused for a window.

Hiding the only window this way is a trap: it persists, so the IDE starts invisible next time.
Use `eclipse_set_ide_visibility` for that, which forgets.

### `eclipse_manage_window`

**Changes what the user sees**: opens or closes a real workbench window, the way *Window > New Window* and a window's close box do.
Writes nothing to the workspace.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `action` | `open` \| `close`, required | | |
| `perspective` | string | the default perspective | For `open`: perspective id or label. |
| `window` | string | the active window | For `close`: window title or a substring of it. |

Both actions answer with every window that exists afterwards, whether it is the active one and its bounds.
An ambiguous title is refused with the candidates rather than guessed, the way every other name here is resolved.

**Closing the last window is refused unconditionally**, because closing it shuts the IDE down and takes this server with it, which nothing outside the machine could undo.
A closing window whose editors have unsaved changes raises a save prompt, which holds the UI thread like any modal dialog, so the wait is capped and running out reports `timedOut` with a pointer to `eclipse_dismiss_dialog`; the window may close once the prompt is answered.

### `eclipse_show_view` and `eclipse_hide_view`

**These change the perspective layout**, which Eclipse remembers across restarts. They write nothing to the workspace.

`eclipse_show_view` opens a view in the active perspective and `eclipse_hide_view` closes one.
Both take `view`, which is an id or the label a person reads, so `Problems` works as well as `org.eclipse.ui.views.ProblemView`.
`eclipse_show_view` also takes `activate` (`true`) and both take `secondaryId`, for views such as the Console that can be open more than once.

A name is resolved by exact id, then by exact label, then by substring, and stops at the first of those that matches anything.
Without the ordering an exact id that also occurs inside three other ids comes back ambiguous.
An ambiguous name is **refused with the candidates** rather than guessed, and a name that matches nothing open is refused with the list of what is open.

There is no tool for closing editors. An editor can hold unsaved work, and losing it is not something a client should be able to do by accident.

### `eclipse_list_perspectives`, `eclipse_switch_perspective` and `eclipse_reset_perspective`

A perspective owns the layout: which views exist, where they sit and how big they are.
`eclipse_show_view`, `eclipse_hide_view`, `eclipse_move_part` and `eclipse_set_part_state` all change the active perspective and nothing else, and Eclipse remembers those changes across restarts.

`eclipse_list_perspectives` reads only. It reports every registered perspective with `open` and `active`, the ids that are open in the window, and honours `filter` and `maxResults`.

`eclipse_switch_perspective` takes `perspective`, an id or the label a person reads, so `Debug` works as well as `org.eclipse.debug.ui.DebugPerspective`.
An ambiguous name is refused with the candidates rather than guessed, the same way `eclipse_show_view` resolves one.
It reports `previousPerspective`, so a caller can put the IDE back, and takes `reset` to start from the registered layout.
Switching is usually cheaper than opening five views one at a time.

`eclipse_reset_perspective` puts the active perspective back to the layout it was registered with, without the confirmation dialog the menu entry shows.
That makes it **the undo** for every layout tool here, none of which can be undone individually.
It also **discards** whatever layout the perspective currently has, which may be one the user built by hand, so it needs `confirm: true` and is worth asking about on somebody's running IDE.

### `eclipse_move_part`

Moves a view into another stack, beside one, or out into a window of its own. This is dragging a tab, without a mouse.
An editor moves too, within the editor area: `left`, `right`, `above` or `below` another editor splits the area, which is how two editors are put side by side, and `stack` puts it into another editor stack.
A view stack or a detached window is refused for an editor, and the editor area for a view, because the workbench renders neither.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `part` | string, required | | Id of the view or editor to move. An editor can also be named by a substring of its tab title; the active editor wins a tie. |
| `target` | string | | A part id, an editor id or tab title, or a stack id. Required unless `position` is `detached`. |
| `position` | `stack` \| `left` \| `right` \| `above` \| `below` \| `detached` | `stack` | |
| `index` | integer | last | Tab position, for `position: stack`. |
| `ratio` | integer, 10 to 90 | 50 | Percent of the target's area the new stack takes, for the four splits. |
| `bounds` | string | `200,200 700x500` | `x,y widthxheight` of the detached window. |
| `activate` | boolean | `true` | Bring it to the front of its new stack. |
| `maxResults` | integer | 50 | Cap on the stacks reported back. |

**It answers with the layout either way.** `layout.stacks` lists every stack the active perspective shows with the parts in it, on success and on refusal alike, so a call with a target that does not exist tells you what does. A stack is addressed by its `id`, and a stack whose id is `null` is still reachable through any part in it, because `target` accepts either.

It reports `previousStack` and `previousIndex` to move the part back, and `eclipse_reset_perspective` puts the whole perspective back.

The moved element is the perspective's placeholder for the view, not the shared part behind it, so the move belongs to the active perspective and does not follow the view into the others.

Only views move. An editor belongs to the editor area, and there is no meaningful place to put it.

The splits build the states that need a particular layout and that nothing else here can reach: a stack with several tabs against one with a single tab, a column narrow enough for the tabs to overflow into the chevron, and a detached view, which is its own shell and is drawn by a different set of CSS selectors again.

### `eclipse_get_display_info`

**Read-only.** Reports the DPI and scaling state of the running IDE: `deviceZoom`, `nativeDeviceZoom`, `effectiveAutoScaleValue`, `customAutoScale`, the display `dpi`, every monitor with its bounds, client area, zoom and primary flag, the SWT platform and version, the GTK version, and the values of `GDK_SCALE`, `GDK_DPI_SCALE`, `GDK_BACKEND`, `WAYLAND_DISPLAY`, `XDG_SESSION_TYPE` and `DISPLAY` as the process actually sees them. It takes no arguments.

**This is the only way to assert that a scaling variant took effect.**
The `zoom` field of `eclipse_screenshot` is `capturedPixels / areaInPoints`, and on GTK that stays 100 even when the device zoom is 200, because SWT points map one to one to device pixels there: what changes is how much logical content fits, not the size of the capture. A visual regression suite that runs the same scenario under several scaling variants and checks the capture's zoom therefore cannot tell a variant that applied from one that was silently ignored, so it compares against the right baseline and proves nothing. `deviceZoom` is the number that moves.

Measured against an identical 100% baseline, on GTK: `GDK_SCALE=2` with `swt.autoScale=100` changed 14.9% of pixels, `GDK_SCALE=2` with autoScale unset changed 1.3%, and `swt.autoScale=200` with no `GDK_SCALE` changed 1.3%. The capture stayed 1600x1000 in all three. `GDK_SCALE` is what scales the layout; `swt.autoScale` on its own rescales images and pins `deviceZoom` to its value, which then hides whatever the display underneath is doing, so setting it globally is a trap.

`customAutoScale` is what separates "the flag was never set" from "the flag was set and had no effect", which the effective value alone cannot say, since an explicit setting and a default of the same number read identically. The monitor list matters as much as the zoom: on a headless Xvfb run it is the only way to tell a virtual screen that came up at the wrong geometry from a scaling flag that was ignored.

`DPIUtil` and the GTK version are reached reflectively, in `DisplayScaling`, because both are SWT internals: one has changed shape across releases and the other exists on a single window system. An IDE where they cannot be reached loses those fields and says so in `scalingNote` rather than failing.

### `eclipse_screenshot`

Captures the IDE as a PNG, writes it to a file and returns the path.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `target` | `part` \| `shell` \| `display` | `part` | |
| `part` | string | | Part id, from `eclipse_list_ui_targets`. |
| `shellTitle` | string | active shell | Title or substring. |
| `activate` | boolean | `false` | Bring the part forward first. |
| `maxWidth` | integer, 100 to 4000 | 1200 | Downscale before writing. |
| `outputPath` | string | a temporary file | |
| `highlights` | array | | Rectangles to draw onto the image after the capture, see below. |
| `includeBase64` | boolean | `false` | Also return the image inline. |

Screenshots earn their place for UI work: layout, theming, dialog rendering, confirming a widget change looks right. For anything textual the other tools answer better and shorter, and a screenshot of the Problems view shows twenty rows of several thousand.

`display` captures whatever else is on the screen, mail and chat included, which is why it is not the default.

A part behind another tab is not rendered at all, so capturing it would produce an empty image. It is **refused** unless `activate` is passed, because activating visibly rearranges the user's IDE and should not be a silent side effect.

With no `shellTitle`, the active shell is meant, and the active workbench window's shell is what answers: `Display.getActiveShell` is null whenever the IDE does not have focus, which is the normal state for a call arriving over HTTP, so the window is found through the workbench instead. A `shellTitle` that matches nothing is refused with the title in the message.

`method` in the answer says how the image was produced. `rootCapture` reads the real screen pixels for the area and crops. `widgetPrint` paints the widget hierarchy instead, which is the fallback used when the first attempt comes back uniform: under a compositing window manager such as mutter, a redirected window's contents live in an offscreen pixmap, so reading the X11 root drawable yields nothing at all. Printing has known GTK gaps, which is why it is the fallback and not the primary path, but a slightly wrong image beats no image.

A shell capture is sized to the shell's client area and composes every visible child of the shell into one picture, each at its own bounds, so the trim bars are in it: the top toolbar as well as the status line, HeapStatus and progress trim at the bottom.

That matters because the trim bars are where a theming defect shows, and they are ordinary styled widgets no other target photographs.

The window decorations, meaning the title bar and the frame around the window, stay out of a widget print: they are drawn by the window manager and are not children of anything SWT can paint. They are in the picture only when `rootCapture` succeeded.

`requestedArea` in the answer names the bounds of what was asked for. When the capture covers less than that, `requestedAreaNote` says what was left out and why, so a shorter image is a reported truncation rather than something a caller notices after cropping.

For `widgetPrint` the canvas is filled with magenta before printing, because anything the print leaves untouched has to stay detectable and white would be indistinguishable from the unstyled widgets a dark theme bug produces. After that check has judged the image, the still-magenta pixels are replaced with the widget's background colour in the image data, so a whole shell capture no longer arrives with every sash and part margin outlined in filler colour; the answer reports how many there were in `unpaintedPixels`, their share of the image in `unpaintedFraction`, and the colour they were filled with in `unpaintedFilledWith`. A UI that genuinely contains pure magenta shows up as a large count rather than as silence.

There is no fallback for `display`, since there is no single widget to paint, so on such a display only `part` and `shell` can be captured.

The uniform-pixel check is what makes this safe rather than quietly wrong: SWT returns a blank image with no error at all on GTK4, and root capture returns blank under compositing, so a screenshot tool that trusts its own output writes an empty PNG and says it succeeded. Here a capture that is uniform after both attempts is refused and nothing is written.

**Highlights.**
`highlights` draws rectangles onto the captured image, each `{path, bounds, color, label, style}`:
`path` is a widget path from `eclipse_get_widget_tree` relative to the capture target, `bounds` is `x,y wxh` in points relative to the capture target, `color` is `#rrggbb` (default `#ff0066`, which reads on light and dark themes), `label` is drawn in a filled box beside the rectangle, and `style` is `outline` (the default) or `fill` (translucent).
`padding` adds air around the rectangle **in points**, one number for every side or `top,right,bottom,left`, applied after the rectangle is resolved and before it is scaled, which is the only way to frame a widget loosely when the rectangle came from a `path`.
`lineWidth` is the outline in pixels, 3 by default, and `labelPosition` is `above` (default), `below`, `left`, `right` or `inside`, for keeping a label off a neighbouring element.
Points are scaled by the zoom the capture was painted at and by the downscale to `maxWidth`, and the answer reports under `highlights` the pixel rectangle each one landed on, whether it was clipped, and the label's box, so a reader can verify the overlay against the image.
The rectangle and the fill are written into the pixels directly rather than through a GC, so a monitor's scaling cannot shift them.
**Selecting a shell.**
`shellTitle` matches by title substring, which is ambiguous when shells share a title: the workbench window and the content assist popup both report the empty title, so `shellTitle: ""` picks the workbench.
`shell` addresses one independent of title, and wins over `shellTitle`: `popup` picks the topmost visible untitled non-workbench shell (the content assist proposals, recognised by their `Table`), an index from `eclipse_list_ui_targets` (`1` or `#1`) picks that shell in the listed order, and the bounds as printed there (`151,334 402x255`) pick the shell at those bounds.
`eclipse_screenshot`, `eclipse_get_widget_tree` and `eclipse_inspect_widget` all take it.
`eclipse_list_ui_targets` reports each shell's `index`, `kind` (`workbench`, `dialog` or `popup`) and `firstControl` (the first `Table`, `Tree`, `StyledText`, `Browser` or `Text` inside it), so the proposal popup is recognisable without guessing from bounds.
`eclipse_get_widget_tree` with `includeRows` enumerates the rows of a `Table` or `Tree` with an `r` prefixed path (`0/r2`), the row `bounds`, `boundsInShell` mapped to the shell, and `selected` for the row the widget has selected, which is how to outline the chosen content assist proposal on a `shell: "popup"` screenshot.

`eclipse_get_text_bounds` and `eclipse_list_annotations` produce the `bounds` of a text range or of a squiggle in exactly this form.
On a HiDPI monitor use a `part` capture (no `includeToolbar`) with the `inPart` bounds: a single part print is pixel-exact, while `includeToolbar` and `shell` captures render at twice the size and are unreliable there (see `docs/platform-bugs.md`).

### `eclipse_run_script`

Runs several tools in order and reports what each answered.
**As destructive as its steps**, since it does whatever they do.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `steps` | array, required | | Each `{tool, arguments, label, expect, continueOnError}`. |
| `atomic` | boolean | `false` | Run every step in one turn of the UI thread. |
| `stopOnFailure` | boolean | `true` | Stop at the first step that errors or fails its expectations. |

**Two things it buys.**
`atomic` runs the whole batch inside one turn of the UI thread, which is the only way a transient state survives from one step to the next: a content assist popup, a hover or a drag closes as soon as the event loop runs between two ordinary calls, so "open the proposals, then read them" cannot be done as two calls and can be done here.
Leave it off for long steps, since the UI is blocked for the whole batch.

`expect` turns a sequence into a check that passes or fails rather than a transcript somebody has to read, which is what makes a scripted IDE test possible from outside.
A path walks the answer with dots and list indices (`widgets.0.selected`), and `name[key=value]` picks an entry of a list by one of its fields (`items[command=org.eclipse.ui.edit.undo].enabled`), which is what keeps a script from breaking when the list gains an entry; the search descends into nested lists, so an item in a submenu needs no path through every level.
A plain value means equality; `{"contains": "x"}` a substring; `{"matches": "regex"}` a regular expression; `{"exists": true}` presence; `{"size": n}` the length of a list.
A failed expectation is reported with the path, what was expected and what was there.

Each step reports `ok`, `millis`, `error` and the tool's own answer as a document rather than a string escaped into one, and the run reports `total`, `passed`, `failed` and `stoppedEarly`.

Do not put `eclipse_restart` in a script: it takes the IDE down and the remaining steps go with it.

### `eclipse_get_context_menu`

Reports what a part's context menu would show for the current selection. Read-only, and **nothing appears on screen**.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `part` | string | active part | Part whose context menu to report. |
| `path` | string | | Report one submenu, by label without its mnemonic, e.g. `Team`, nested with `/`. |
| `maxDepth` | integer, 1 to 6 | 2 | How many levels of submenu to open. |
| `maxResults` | integer | 300 | |

A context menu is a native window rather than an SWT shell, so `eclipse_screenshot` cannot capture it, and the `Menu` does not exist until something asks for it, so `eclipse_get_widget_tree` finds nothing either.
This sends the control the `MenuDetect` a right click sends, populates the menu with a `Show` event, walks it, and hides everything it showed; the menu is never made visible, so no native grab is taken.

Each item reports its `label` without the mnemonic or accelerator, `enabled`, `separator`, `hasSubmenu`, `selected` for check and radio items, and the `command` behind it, read from the contribution (a 3.x `CommandContributionItem` or an e4 handled item, asked through the shape they share rather than by importing an internal type).

This is a different question from `eclipse_run_workbench_command` enablement: **an item can be enabled and contributed to no menu at all**, and a command missing from the menu a person actually opens is the bug the enablement answer hides.
Set the selection first with `eclipse_set_selection`; `path` also keeps a large menu cheap, since every level costs a `Show` on each submenu.

### `eclipse_get_selection` and `eclipse_set_selection`

`eclipse_get_selection` is read-only; **`eclipse_set_selection` changes what is selected in the IDE** and reports the previous selection so it can be put back.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `part` | string | active part | Part whose selection to set. `eclipse_set_selection` only. |
| `elements` | array, required | | Workspace paths (`/org.eclipse.compare`), project names (`g`), or widget-tree row paths (`0/0/0/r7`). Empty clears the selection. |
| `reveal` | boolean | `true` | Scroll the viewer to the selection. |
| `activate` | boolean | `true` | Activate the part first, so the selection reaches the handler evaluation context. |

This is what makes a command's enablement testable: set a selection, then ask `eclipse_run_workbench_command` with `dryRun` whether the command is enabled for it.
It is also the only way to build a selection here at all, since a view may register no Select All handler and `eclipse_press_key` cannot deliver `Ctrl+A` on a backgrounded Wayland session.

**Two selections are reported on purpose.**
`handlerSelection` is the `ACTIVE_CURRENT_SELECTION` variable of the evaluation context, which is what an `enabledWhen` expression is tested against; `serviceSelection` is what the active page's selection service holds.
They disagree whenever a part has not published its selection into the context yet, and an enablement question answered from the wrong one is answered wrongly.
Each element carries its class, label, whether it adapts to `IResource`, and the resource's path, project and accessibility, since that is what most enablement expressions test.

`eclipse_set_selection` reports what the selection service holds **afterwards** rather than what was requested: a viewer silently drops an element it does not have, and a selection that did not take would otherwise show up only as a wrong enablement answer later.
A closed project resolves like any other, since whether a selection may contain one is often exactly what the enablement test is about.

### `eclipse_type_text` and `eclipse_press_key`

**Both change the IDE.** They set up an editor state a client cannot reach by reading what is already there.

`eclipse_type_text` takes `text` and an optional `part`, and inserts the text at the caret of the active (or named) text editor by editing its document, the way a typed key ends up in the file: a current selection is replaced, the caret moves behind the text, and the editor becomes dirty.
It is deterministic and independent of focus and keyboard layout, so it is the one to use for entering characters.
It does not open content assist or run any key binding, and nothing is saved.

`eclipse_press_key` posts real key events through `Display.post` to the focused control, for what the document API cannot do: `Ctrl+Space` to open content assist, `Escape` to close a popup, `Enter` to accept a proposal, arrow keys to move the selection in a proposal table, `Tab`, `BackSpace`.
It takes a `key` like `Ctrl+Space`, `Escape`, `Down`, `Enter`, or a single character, and a `count`.
`Display.post` goes to whatever window has OS focus, so the tool refuses when the IDE is not the active window rather than sending the key elsewhere, and it reports whether the post was accepted and whether focus was inside the IDE.
On Wayland the compositor commonly ignores a posted event even when the post is accepted, which the answer warns about; verify the effect with a screenshot, and for plain characters use `eclipse_type_text`.

The two complete the content-assist workflow: `eclipse_type_text` the trigger text, `eclipse_press_key` `Ctrl+Space`, `eclipse_screenshot` the popup with a highlight on the selected `Table` row, then `eclipse_press_key` `Escape` or `eclipse_dismiss_dialog` to close it.
`eclipse_dismiss_dialog` with no `shellTitle` now closes the content assist popup too: when no modal dialog is open it finds the transient popup, which has no title and carries the proposal `Table`, and reports `kind` as `popup` rather than `dialog`.

### `eclipse_get_text_bounds` and `eclipse_list_annotations`

Both read-only, both default to the active editor and accept `part` for another open editor.

`eclipse_get_text_bounds` takes `line` (1-based) with an optional `column` and `length`, or a document `offset` with `length`, and reports the rectangle of that text in points relative to the editor part (`inPart`), to the part stack (`inPartStack`, what `eclipse_screenshot` captures with `includeToolbar`), to the shell and to the text widget, plus `lineHeight`, `baseline` and the placement of the text widget itself.
A range that folding hides is reported as not `visible` rather than at a wrong place; a range scrolled out of view is `scrolledOut` with the rectangle it would occupy.

`eclipse_list_annotations` lists the annotations of the editor, which is what the squiggles, the ruler icons, the overview ruler marks and the folding markers are drawn from: `type` (`org.eclipse.jdt.ui.error`, `org.eclipse.ui.workbench.texteditor.spelling`, `org.eclipse.projection`, ...), `text`, `offset`, `length`, `startLine`, `endLine`, `collapsed` for folding annotations, and with `includeBounds` (the default) the same rectangles `eclipse_get_text_bounds` reports.
Filter with `typePattern`, a regular expression matched anywhere in the type, and with `fromLine` and `toLine`; `maxResults` defaults to 100 and `total` and `truncated` are reported.

The two together are what lets a client outline one squiggle: list the annotations, take `bounds.inPart` of the one it wants, and hand it to `eclipse_screenshot` as a highlight on a `part` capture. The vertical ruler, line numbers, overview ruler, squiggles and caret are all inside the part, so `includeToolbar` is not needed for them.

### `eclipse_check_for_updates`, `eclipse_update`, `eclipse_install`, `eclipse_get_provisioning_status`

**These modify the installation.**
`eclipse_check_for_updates` changes nothing and reports which installed units have an update, from which version to which, and the configured repositories with the timestamp of the metadata behind each one.

Both take `units`. Naming the installed units you care about scopes the whole operation: `ProvisioningContext.getInstallableUnitSources` is asked which repositories can supply them, only those are refreshed, and the resolution is restricted to them with `setProvisioningContext`, so unlisted repositories are never loaded at all. On an IDE configured with a dozen update sites that is one network round trip instead of a dozen. Omitting `units` keeps the broad question broad, because narrowing it silently would be the same class of mistake as reporting a stale cache as up to date.

Only the metadata manager is ever refreshed, never the artifact manager. An update check does not read artifact metadata, and refreshing both is what makes a check cost twice what it needs to.

It re-reads that metadata first, by default. p2 caches repository metadata, and a cached miss comes back as "no updates found", which is exactly what a genuinely current IDE reports, so a stale cache is invisible and looks like success. That is worst in the self-update workflow these tools exist for: publish a build, check for updates, and be told there is nothing new. `refresh: false` gives the fast cached answer instead, and then says so in a `caveat` rather than letting the miss pass as a verdict. `eclipse_update` refreshes for the same reason, since otherwise it resolves against the cache and finds nothing to apply.
**Updating the server itself is refused unless `acknowledgeSelfUpdate` is passed.** The provisioning job runs inside the bundles being replaced, so a self update stops the bundle answering the request, and if anything then fails there is nothing left running to finish the update or to report why. The result is an IDE with no server, no way in, and no recovery except restarting Eclipse by hand at the machine. That was survivable while the IDE was something a person could see; it is not once the window can be hidden. The connection dropping is expected and fine, and a client can wait for it to come back. The bundle staying stopped is the failure, and it has no path back from outside.

`eclipse_update` applies updates to units that are already installed, from repositories already configured. `eclipse_install` adds a new unit.
Both run as jobs and return an `operationId` polled through `eclipse_get_provisioning_status`, because p2 resolution can take minutes on a slow mirror.

When a resolution fails, the answer carries p2's own reasons rather than only the top level message, which for p2 is the useless string `Operation details`: the children of the resolution status are flattened into the reply, one line per conflict, capped at twenty with the count shown, and the full status is logged as a warning so it survives in `eclipse_get_log_entries` even when only the short answer was seen.

Both take `trustUnsigned`, which is **accepted by default**. p2 asks whether to trust unsigned content or content signed by a certificate this IDE does not trust, and the IDE answers that with a modal dialog. During these calls that dialog is replaced by an answer, because a job blocked on a prompt is indistinguishable from a slow download and an unattended update would otherwise hang until the call timed out. There is nobody to click that dialog on an install this server performs, and refusing by default made a locally built repository uninstallable however the caller asked, which is the case these tools exist for. Accepting is not bounded by which sites are configured, because `eclipse_add_repository` can configure a new one: whoever can call these tools decides what gets installed. Nothing reaches the IDE's permanent trust store, `trustAlways` is never returned, and whatever was accepted is reported, so a trusted install is auditable rather than silent. Pass `trustUnsigned: false` to refuse instead, which is then reported in `blockedBy`. Signing the artifacts on the update site removes the question for every consumer instead of teaching one client to click through it.

Whatever p2 asked about is listed in `trustPrompts`, each entry naming the unsigned artifact, the certificate subject or the PGP key id, prefixed `REFUSED` when `trustUnsigned` was false. That list is capped at twenty five entries, because an SDK install asks about hundreds of artifacts and the list is evidence rather than a manifest: `trustPromptsTotal` is how many there were and `trustPromptsTruncated` says whether the cap was reached. `trustedUnsigned` is whether anything was actually accepted, and `trustNote` says what that means.

`eclipse_install` **refuses a repository the IDE is not already configured with**, and lists the ones that are. Installing fetches and runs code from the network, which is a larger step than any other tool here takes, and adding a new source is a decision for the person at the IDE. Add it under *Preferences > Install/Update > Available Software Sites* first.

This is self-updating machinery, and the descriptions say so: if a bad build lands, the tools that would fix it are the tools that just broke. Two things make that recoverable. `eclipse_restart` is in a different bundle and does not depend on the provisioning tools, so a half-applied update can still be restarted out of. And every result carries `previousConfiguration`, the timestamp to revert to from *Help > About > Installation Details > Installation History*, which works with no server at all.

### `eclipse_uninstall`

**Uninstalls software from the running installation**, and runs as a dry run unless `dryRun` is set to false.
It is the way back out of `eclipse_install`, which is otherwise a one way door.
The case that prompted it: a feature was installed at version `1.0.0.202608261121`, then rebuilt under a new qualifier, so the still installed feature pinned its bundles at the old version while every unit of the new build wanted the new one.
Install and update both failed to resolve from then on, and the only way out was a person clicking through *Help > About > Installation Details > Uninstall*.

| Argument | Default | Meaning |
|---|---|---|
| `unit` | required | Installable unit id, as `eclipse_get_installation` reports it. A feature id usually ends in `.feature.group`. |
| `version` | | Exact version, when more than one version of the unit is installed. |
| `dryRun` | boolean, `true` | Resolve and report what would be removed without changing anything. |
| `maxResults` | 100 | Cap on the reported additions and removals, 1 to 2000, with `total` and `truncated`. |

A unit that is not installed is refused by name rather than handed to p2 as an exception.
When several versions of the unit are installed, the exact one has to be named.

**Read the dry run before applying it.**
Removing one feature can drag out bundles that something else still needs, or fail because something else depends on it, so the answer reports what the resolved operation would actually remove, taken from the plan p2 computed, not merely the unit that was asked for.
One feature going in and eleven bundles coming out is exactly the thing worth learning before committing.
Each change entry is tagged `removed` or `added`.

Applying runs as a job and returns an `operationId` polled through `eclipse_get_provisioning_status`.
Like every install here, the removal takes effect on the next IDE restart; `eclipse_restart` works independently of this tool, including after a half applied operation that took this bundle with it.

### `eclipse_exit`

**Shuts the IDE down. Nothing here can start it again.**
That is the whole difference from `eclipse_restart`: the process ends, this server ends with it, and bringing it back is the caller's job from outside. It exists for a harness that starts a throwaway IDE per run and has to take it down afterwards; the alternative was killing the process group, which is harder than it sounds, because the Eclipse launcher execs a JVM and the direct child exits first, so a naive waiter concludes the IDE is gone while it keeps running and holds the port and the workspace lock.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `save` | boolean | `false` | Save dirty editors first. |
| `force` | boolean | `false` | Exit anyway, discarding unsaved work. |

The answer reports what was requested, never the outcome, for the same reason the restart answer does: it goes out two seconds before the shutdown, so a dropped connection right after a successful result is expected. Confirm the exit from outside by watching the process or the port, not by asking this server.

It shares the guard with `eclipse_restart`, and for the same reason. `IWorkbench.close` is a cancellable close that prompts for every dirty part in every window, and a veto leaves the JVM up, so an unguarded exit on an IDE nobody is watching stalls in an invisible dialog rather than failing. `force` discards that work outright so the platform has nothing to prompt about, and a modal dialog is better cleared with `eclipse_dismiss_dialog` than forced past. Builds are cancelled and launches this server started are terminated first, because a launched JVM that outlives the IDE keeps its workspace lock with nobody left who knows where it came from.

An exit the platform vetoes leaves the IDE running and goes to the Error Log, and is reported as `previousExitFailed` by the next call if there is one.

### `eclipse_restart`

**Restarts the IDE. The connection will drop by design.**
The tool answers first and restarts two seconds later, so a dropped connection immediately after a successful result is the expected outcome rather than a failure. Reconnect with the same bearer token: it lives in user scope and survives both restarts and p2 updates.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `save` | boolean | `false` | Save dirty editors first. |
| `force` | boolean | `false` | Restart anyway, discarding unsaved work. |
| `splash` | boolean | `true` | `false` comes back without the splash screen. |
| `clean` | boolean | `true` after a hot install or substitution in this session, else `false` | Relaunch with `-clean`, discarding the registry and resolver caches. |
| `workspace` | string | current one | Absolute path of the workspace to start into. Created when it does not exist. |

**A hot install or a substitution makes the next plain restart untrustworthy.**
The extension registry cache written at shutdown describes the contributions of the bundles that were refreshed, and the editors the workbench restores at the next start then fail with `InvalidRegistryObjectException` while a freshly opened one works.
So after `eclipse_install_bundle` in mode `hot` or any `eclipse_substitute_bundle` change, `eclipse_restart` adds `-clean` by default, which costs a few seconds of startup and rebuilds both caches; the answer carries `clean` and `cleanReason`, and `clean: false` keeps the caches when that is wanted.

`splash: false` appends `-nosplash` to the arguments the workbench hands the launcher for the next start, which is the same channel `Workbench.buildCommandLine` uses to pass `-data`.
`splashSuppressed` in the answer reports whether the argument was added, and deliberately not whether the splash then stayed away: the splash is painted by the native launcher before the JVM exists, so nothing inside the IDE can observe the result.
That the launcher does honour it was confirmed from outside on GTK/Linux, by comparing the relaunched process: `-showsplash` is gone from the new command line and the launcher carries `-nosplash`.
When the argument cannot be added the restart still happens, with `splashSuppressed` false, because a restart with a splash beats no restart at all.

It refuses when editors have unsaved changes or a modal dialog is open, listing them, since restarting under an open dialog loses whatever is in it.

The answer names the `workspace` the IDE will return to. If it comes back asking which workspace to use, the relaunch lost its arguments, which is what `IWorkbench.restart()` does; `restart(true)` is what preserves `-data`.

**Switching workspaces.**
`workspace` sends the IDE into another workspace instead of back into the current one, which is what gives a measurement a workspace of its own rather than the one somebody works in.
The answer reports `workspace`, `previousWorkspace` and `workspaceChanged`, so a caller that switches can find its way back.
The path has to be absolute, because the launcher would resolve a relative one against a working directory nobody here knows, and it is created when it is missing, because a path that is not there opens the workspace chooser and waits for a person.

A directory is not enough on its own, and this is the part that is easy to get wrong.
Whether the server runs at all is an *instance* preference, so it belongs to the workspace rather than to the installation.
Switching into a workspace that has never had the server switched on would bring the IDE up with nothing listening and no way left to ask why, so the enabled flag, the port and the call timeout are written into the target workspace before the relaunch.
The answer reports that under `server`, with `carriedOver` false when the workspace already had settings of its own, which are then left alone.
The bearer token is not copied because it does not need to be: it lives in user scope and is the same in every workspace.

The discovery file is per workspace too, so a client that reads `endpoint.json` has to read the one under the new workspace's `.metadata` after the switch.
The port does not change, which means reaching `8642` proves nothing on its own: compare `startedAt`, and compare it against the *target's* previous value, since a workspace switched into twice still holds the file from the first visit.

In development mode the switch is refused rather than attempted.
The workbench forces a plain restart there and keeps the command line of the launch it came from, so the relaunch arguments are dropped and the IDE would come back into the old workspace with nothing reporting that the switch did not happen.

### `eclipse_install_bundle`

**Changes the running IDE.**
Installs one bundle jar into the live OSGi framework in seconds, with no p2 repository, no build and no restart.
This is the fast loop for seeing a patched plug-in in a running IDE: build the jar, hand it over, and the extension registry has its contributions a moment later.
The p2 path (`eclipse_add_repository`, then `eclipse_install`, confirmed by `eclipse_get_installation`) is slower and needs a repository and usually a restart, but it is what survives everything described below.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `jar` | string, required | | Absolute path to an OSGi bundle jar. |
| `mode` | `hot` \| `dropins` | `hot` | `hot` acts on the live framework, `dropins` copies for the next start. |
| `dryRun` | boolean | `true` | Reports what would happen and changes nothing. |
| `start` | boolean | `true` | `hot` only. Start the bundle after installing; fragments are reported instead of started. |
| `allowSelf` | boolean | `false` | Allow an operation whose refresh would take this server's own bundles with it. |
| `maxResults` | integer, 1 to 2000 | 200 | Cap on the `refreshed` and extension point lists. |

```json
{"symbolicName":"com.example.ui","previousVersion":"1.0.0","version":"1.0.1","mode":"hot",
 "outcome":"updated","state":"ACTIVE","resolved":true,"fragment":false,"started":true,
 "refreshed":[{"symbolicName":"com.example.core","version":"1.0.0"}],"total":9,"truncated":false,
 "extensions":{"pluginXmlInJar":true,"points":[{"extensionPoint":"org.eclipse.e4.ui.css.swt.theme","count":1}],
               "total":1,"truncated":false},
 "notes":["A hot install is invisible to p2: ..."]}
```

`hot` installs through `BundleContext.installBundle`, or replaces content in place with `Bundle.update` when the symbolic name is already installed, and then runs `FrameworkWiring.refreshBundles`.
The refresh restarts every bundle wired to the one being replaced, which for a low level bundle is a large part of the IDE, so the dependency closure is reported as `refreshed` before anything happens and `dryRun` defaults to `true`.
Updating to the same version is allowed when the file content differs, which is what iterating on a patched bundle looks like, and the answer then says explicitly that the version did not change.
Identical content under an identical version is refused, because the framework would reject it as a duplicate anyway.
A bundle that installs but does not resolve comes back with its state, a `resolutionError` where one was reachable, and `resolved: false`; that is the common failure mode and a bare state number would say nothing.

After a successful hot install the answer carries an `extensions` object: what the extension registry attributes to the bundle right now, one entry per extension point with the count of extensions it carries, capped like every other list here.
For a bundle that is CSS and a `plugin.xml` and no Java at all, this is the part that says whether the hot install was worth anything.
A jar that carries a `plugin.xml` but ends up with nothing attributed has that said plainly in its notes, because it means the contribution did not take while the IDE runs and a restart is needed.
One cause is knowable in advance: the registry reads contributions from singleton bundles only, so a jar whose `Bundle-SymbolicName` lacks `singleton:=true` has its `plugin.xml` ignored outright, and the note names that instead of sending anyone hunting for a restart that will not help.
The report comes with its limit, next to it and in the tool description: reaching the registry is not the same as being usable.
Some consumers read the registry once at startup and keep their own list, and the e4 theme engine is believed to be one of those, so a newly contributed theme can be in the registry and still invisible to the thing that is supposed to offer it.

**A hot install is a throwaway test that a restart undoes.**
It is invisible to p2: `eclipse_get_installation` does not show it, and Eclipse's simpleconfigurator reconciles the framework against `bundles.info` at every start, so the original bundle comes back.
That belongs in the answer of every successful hot install, not only here, because it is exactly the part a caller who has never been bitten cannot know.

`dropins` copies the jar into the installation's `dropins` directory, creating it if needed.
It is the opposite trade: it needs a restart to take effect and survives one, and p2's reconciler is what picks it up.
An installation directory that is not writable, normal for shared or packaged installs, is refused up front instead of failing halfway through the copy.

Classes already loaded keep running after an update: a view that is open shows the old code until it is closed and reopened.
And replacing a bundle whose refresh would stop this server's own bundles is refused by default, because the answer could never be delivered through a server the refresh is about to stop.
With `allowSelf` the install happens at once, the answer names what is about to happen, and the refresh follows two seconds later in a job, so the response is on the wire before the server goes down; reconnecting afterwards is the caller's job, and the effect of the refresh cannot be reported, because nothing will be left to report it.
This tool never uninstalls anything.

### `eclipse_substitute_bundle`

**Makes the IDE run a workspace project's bundle in place of the installed one at the next restart**, by packing the project and pointing this installation's `bundles.info` line at the packed jar.
Changes the installation, not the workspace, and runs as a dry run unless `dryRun` is set to false.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `action` | string | `status` | `substitute`, `restore`, `status`, `cleanup` or `repair`. |
| `project` | string | | Workspace project to pack, for `substitute`. |
| `jar` | string | | A packed jar to act on instead. |
| `bundle` | string | | For `restore`: put back only this bundle's line and leave other substitutions alone. |
| `dryRun` | boolean | `true` | Shows the exact line before and after. |

**This is the only way in for most of the SDK.**
A hot install through `eclipse_install_bundle` is invisible to anything that reads the registry once at startup, the theme engine among them, and the dropins directory cannot replace a bundle that belongs to an installed feature, because a feature demands its bundles at an exact version, which covers nearly everything in an SDK.

**The risk is real.**
A `bundles.info` that names a jar which is not there leaves the bundles that need it unresolvable, and if that bundle is one the framework itself needs, the IDE does not start and no tool here can reach it.
Short of that the IDE still runs, and `action` `repair` points such a line back at the installed jar.

Never delete a packed jar by hand.
`action` `cleanup` does it and re-reads `bundles.info` first, because that file is rewritten at every start and by other sessions, so a jar that looks unreferenced can be the one the next start loads.

The original line is recorded, so `action` `restore` needs nothing from the caller.
It restores every recorded substitution of the installation, and the record is per installation, which several IDEs and sessions share: a restore meant for one bundle once undid another session's substitution of `org.eclipse.jface.text` in the same install.
Pass `bundle` to put back one bundle's line and leave the rest in place.
`action` `status` reports what is substituted right now, checked against `bundles.info` rather than believed from the record, including a substitution another session made, and under `referencingSubstitutedJars` every line that points at a packed jar even when nothing here recorded it, which is what stops somebody debugging an IDE that is not running what its `plugins` directory holds.

**The version field is what makes it take effect.**
`simpleconfigurator` matches a bundle on symbolic name plus version, so a line that keeps the installed version and only changes the path is read as a bundle already installed and the path is never looked at, which is a substitution that does nothing while every check on the file says it is in force.
The version of the substituted jar is therefore written into the line.
Ask `action` `status` for the `running` field, which reports what the framework has actually loaded rather than what the file says, and believe that one.

### `eclipse_hot_code_replace`

**Changes the running JVM.**
Replaces the bytecode of classes the IDE is running with the class files a workspace project or a directory holds, in place and without a restart.
This is the loop for putting a trace statement or a changed method body into a plug-in the IDE is running, this server included: edit, let the builder write the class file, hand the names over, and the next call runs the new body.
The debugger's hot code replace does the same thing for a program launched from the IDE; this does it for the IDE itself.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string | | Workspace Java project whose build output holds the new class files. Its `META-INF/MANIFEST.MF` names the installed bundle. |
| `directory` | string | | Absolute path of a class file root such as `target/classes`, instead of `project`. |
| `classes` | array of string | | Fully qualified names to replace. Omitted, every class file newer than the bundle's installation or than the last replace through this tool is taken. |
| `includeNested` | boolean | `true` | Also replace the nested and anonymous classes beside each named class. |
| `bundle` | string | project manifest | Installed bundle whose classes are replaced. Without one, every loaded class of that name is replaced whatever loaded it. |
| `dryRun` | boolean | `false` | Report which class files would be used and change nothing. |
| `java` | string | the IDE's JDK | Java executable for the one-off attach helper. |
| `maxResults` | integer, 1 to 2000 | 200 | Cap on the classes taken when `classes` is omitted. |

```json
{"project":"com.vogella.eclipse.mcp.jdt","outputRoots":["/home/user/git/mcp/plugins/com.vogella.eclipse.mcp.jdt/bin"],
 "bundle":"com.vogella.eclipse.mcp.jdt","bundleVersion":"0.2.0.202609031200","bundleInstalledAt":"2026-09-03T10:00:12Z",
 "selection":"explicit","total":1,"truncated":false,"dryRun":false,
 "agent":{"how":"helperProcess","helperMillis":812,"agentJar":"/home/user/workspace/.metadata/.plugins/com.vogella.eclipse.mcp.jdt/hotswap-agent.jar"},
 "replaced":1,"atomic":true,
 "redefined":[{"class":"com.vogella.eclipse.mcp.jdt.internal.GetClasspathTool","classFile":"/home/user/git/mcp/plugins/com.vogella.eclipse.mcp.jdt/bin/com/vogella/eclipse/mcp/jdt/internal/GetClasspathTool.class",
               "classFileModified":"2026-09-03T10:41:05Z","sourceNewerThanClass":false,"wasLoaded":true,"bytes":9120,"owner":"com.vogella.eclipse.mcp.jdt"}],
 "failed":[],
 "notes":["Threads already inside a replaced method keep running its old body until it returns; static initializers do not run again.", "..."]}
```

The limits are the JVM's, and they are the same ones the debugger has.
They apply to what the compiler generated, not only to what the source says: a lambda compiles to a synthetic method whose name javac and the Eclipse compiler spell differently, so a class with a lambda compiled by javac is refused as having added a method when the running copy was built by Tycho or by the workspace builder, which both use the Eclipse compiler.
The project's build output is therefore the safe source, and for a directory the batch compiler that ships in every IDE compiles a single file the same way:

```bash
java -jar <install>/plugins/org.eclipse.jdt.core.compiler.batch_*.jar -25 -proc:none -cp "$(ls <install>/plugins/*.jar | tr '\n' :)" -d out Foo.java
```

Only method bodies, the constant pool and attributes can change.
A class whose fields, methods, signatures, supertypes or modifiers changed is refused with the JVM's own reason under `failed`, for example `UnsupportedOperationException: class redefinition failed: attempted to add a method`, and the class keeps running as it was.
A batch is applied atomically; when it fails the classes are retried one by one so the answer can name the culprit, and `atomic` is then false.
A thread already inside a replaced method finishes the old body, static initializers do not run again, and a class the installed bundle does not have cannot be added, so a new class, a new extension or a new dependency still needs `eclipse_install_bundle`, `eclipse_substitute_bundle` or an install.
A restart puts the installed code back.

The class files come from the project's build output, so save and let the builder run first; the tool waits for a build in flight and reports `sourceNewerThanClass` per class when the file is behind the source, which is what a call made before the builder wrote it looks like.
With `classes` omitted the selection is every class file newer than the bundle's installation time or than the last replace through this tool, whichever is later, which is what "everything I changed since" means in practice; `changedSince` in the answer says which time was used.
Every class is loaded through the installed bundle before it is redefined, so a class nobody had touched yet still gets the new body when it is first used, rather than the old one from the jar.
Without a bundle to load through, only classes something has already loaded can be replaced, `wasLoaded` says which case applied, and `owner` names the bundle that holds each class.

The first call loads a Java agent into the IDE.
A JVM refuses to attach to itself, so a helper JVM is started from the JDK the IDE runs on, attaches by pid and loads the agent jar, which the tool writes into its own state location from the bundle's class files; that takes about a second and `agent` reports which route was taken.
The helper needs the `jdk.attach` module, so on an IDE running on a jlinked JRE without it pass `java` pointing at a JDK.
An IDE started with `-XX:-EnableDynamicAgentLoading` refuses agents outright, and the tool says so before starting anything; JDK 21 and later print a warning about dynamically loaded agents to the IDE's own stderr, which is expected.

## Contributing a tool

Tools are contributed through the `com.vogella.eclipse.mcp.core.tools` extension point:

```xml
<extension point="com.vogella.eclipse.mcp.core.tools">
   <tool class="com.example.MyTool"/>
</extension>
```

`com.example.MyTool` implements `com.vogella.eclipse.mcp.core.IMcpTool`.
The contract for an implementation:

* it is called on a worker thread, never on the UI thread, and never holding the workspace lock
* it must not modify the workspace and must not open a dialog
* to reach the UI, hand work to `Display.asyncExec` and wait on a future with a short timeout, the way `eclipse_get_editor_context` does
* the server aborts any call that has not finished after 30 seconds

## Bundles

| Bundle | Tools | Contains | Depends on |
|---|---|---|---|
| `com.vogella.eclipse.mcp.core` | 30 | `IMcpTool`, the registry and the extension point, plus the workspace, file, build, preference, log, command and flight recording tools | `org.eclipse.core.runtime`, `org.eclipse.core.resources`, `org.eclipse.search.core` |
| `com.vogella.eclipse.mcp.server` | 0 | The MCP protocol handling, the embedded Jetty, the bearer token filter and the endpoint file | MCP SDK, Jetty, core |
| `com.vogella.eclipse.mcp.ui` | 32 | The workbench tools: editor context, commands, views, perspectives, themes and CSS, screenshots, widget inspection, sampling and restart, plus the preference page and the startup hook | `org.eclipse.ui`, `org.eclipse.swt`, `org.eclipse.jface`, the e4 workbench and CSS bundles, `org.eclipse.compare`, core, server |
| `com.vogella.eclipse.mcp.jdt` | 16 | The Java model tools: declarations, references, hierarchies, source, search, and the refactoring, clean up, format and test running tools | `org.eclipse.jdt.core`, `org.eclipse.jdt.core.manipulation`, `org.eclipse.jdt.launching`, `org.eclipse.jdt.junit.core`, `org.eclipse.ltk.core.refactoring`, core |
| `com.vogella.eclipse.mcp.debug` | 8 | The breakpoint tools, the debug session tools and the session registry | `org.eclipse.jdt.debug`, `org.eclipse.debug.core`, `org.eclipse.jdt.core`, core |
| `com.vogella.eclipse.mcp.p2` | 8 | The provisioning tools: update, install, uninstall, the repository tools and the operation registry | the `org.eclipse.equinox.p2.*` bundles, core |
| `com.vogella.eclipse.mcp.pde` | 7 | The plug-in tools: bundle info, manifest editing, dependency analysis, execution environments, target platforms and resource lookup | `org.eclipse.pde.core`, `org.eclipse.jdt.core`, `org.eclipse.osgi`, core |
| `com.vogella.eclipse.mcp.git` | 3 | The repository status, checkout and registration tools | `org.eclipse.jgit`, `org.eclipse.egit.core`, both optional, core |

`com.vogella.eclipse.mcp.core` deliberately has no reference to the MCP SDK, to Jetty or to any UI bundle, so that the tool API stays a candidate for the Eclipse Platform.

## Commercial support

[vogella GmbH](https://vogella.com/services/) builds and maintains this server and offers training and consulting around Eclipse, the Eclipse platform and its tooling, and AI based Java cleanup [for legacy code and code optimization](https://vogella.com/services/).

If you use this server in earnest and something is missing, saying so is welcome either way: the issues and pull requests here are open, and the commercial route exists for work that wants a schedule attached.

## Not in this iteration

MCP resources and prompts; a stdio transport.
