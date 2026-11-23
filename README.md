<img src="docs/images/logo256.png" alt="" width="128" align="right">

# Eclipse MCP Server

Turns a running Eclipse IDE into an [MCP](https://modelcontextprotocol.io) server, so that external LLM clients (Claude Code, Cursor, any MCP-capable agent) can ask the IDE for what they cannot cheaply reconstruct from files: the resolved Java model, the builder's problem markers, the debugger and the live workbench.

Built and maintained by [vogella GmbH](https://vogella.com/services/), and used in our consulting and in our AI based Java cleanup services [for legacy code and code optimization](https://vogella.com/services/).

The server is **disabled by default**, listens on `127.0.0.1` only and rejects every request without the bearer token.
Most tools are read-only; every tool that changes something says so in its own description, and those that can default to a dry run do.

<p align="center">
  <img src="docs/images/splash-animated.webp" alt="The Eclipse MCP Server splash screen" width="600">
</p>

## Installing

In Eclipse, choose *Help > Install New Software*, add

```
https://vogellacompany.github.io/eclipse-mcp-server/
```

and install the **Eclipse MCP Server** feature.
It needs Eclipse 2026-06 or newer: the Java tools build on JDT APIs that release added, so on an older IDE the install is refused.
The site lists the newest build only.

## Building

Requires JDK 25; the Maven wrapper brings Maven 3.10.1.

```bash
./mvnw clean verify
```

The p2 repository ends up in `update-site/com.vogella.eclipse.mcp.repository/target/repository`.
To work on the code in Eclipse, import the projects with *Search for nested projects* enabled and set `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` as the active target platform.

Every push to `main` publishes to the update site.

## Enabling the server

*Preferences > General > MCP Server*:

* **Enable MCP server**, off by default
* **Port**, `8642` by default
* **Tool call timeout**, `30` seconds by default
* **UI thread wait**, `25` seconds by default: how long a tool waits for a busy UI thread before it withdraws the request, capped by the tool call timeout

Changes apply immediately.
The page also shows the URL, the bearer token and the discovery file, and can regenerate the token.
By default, if the configured port is taken, the server stays down and the page says why.
To enable best-effort port reuse, add `-Dcom.vogella.eclipse.mcp.server.ephemeralPort=true` after `-vmargs` in `eclipse.ini`; the page then disables the port field, and the stored value applies again once the property is gone.
Every successful startup saves its listening port as `lastPort` in `<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/port.properties`, regardless of mode.
Fixed-port mode always binds only to the configured preference and never reads the saved port for binding; ephemeral mode tries the saved port first, unless it equals the configured fixed port, in which case it requests an OS-assigned port.
If that port is occupied, it immediately asks the operating system to assign another; failures reading or writing the file do not prevent startup.
The actual endpoint remains in `endpoint.json`, which clients must rediscover after every restart.

### In an RCP application

The server starts through a declarative service, so an RCP application needs `org.apache.felix.scr` and a workspace, but no `org.eclipse.ui.startup`.
Install `com.vogella.eclipse.mcp.core`, `com.vogella.eclipse.mcp.basic`, `com.vogella.eclipse.mcp.server` and the third party bundles the feature lists; specialized provider bundles are optional and each adds its own tools.
`com.vogella.eclipse.mcp.ui` also resolves in a pure E4 application that ships the `org.eclipse.ui` bundles it requires, which then gets the widget, screenshot, keyboard, dialog and settle tools, with parts named by their model element id; the tools built on editors, views, perspectives and workbench commands need the 3.x workbench and say so.
Without the ui bundle there is no preference page, so enable the server in `plugin_customization.ini`:

```
com.vogella.eclipse.mcp.server/enabled=true
com.vogella.eclipse.mcp.server/port=8642
```

The product only reads `plugin_customization.ini` when it points at it, with the `preferenceCustomization` product property or `-pluginCustomization <file>` on the command line.

The server bundle's built manifest requires the `osgi.component` extender, so without `org.apache.felix.scr` in the product it does not resolve.
A product materialized by Tycho does not start bundles on its own, so start `org.apache.felix.scr` (`org.apache.felix.scr@2:start` in `config.ini`) for the server, and `org.eclipse.core.runtime@start` and `org.eclipse.equinox.common@2:start` for the application itself, in the `.product` configuration or as `osgi.bundles` entries; an application whose runtime bundles are not started fails with "Unable to acquire application service".

The ui bundle is optional: `core`, `basic` and `server` run the server with the workspace, file, build and log tools but no UI tools.
Adding `com.vogella.eclipse.mcp.ui` requires the 3.x workbench and the other bundles listed in its `META-INF/MANIFEST.MF`, but not `org.eclipse.ui.ide`, `org.eclipse.compare` or `org.eclipse.debug.ui`, so it adds no p2, navigator or Debug perspective to a product; leave it out unless you want the screenshot, widget and keyboard tools, or in an IDE the editor and view tools.
`com.vogella.eclipse.mcp.ide` holds the IDE-only tools, `eclipse_open`, `eclipse_open_compare`, `eclipse_run_launch_shortcut` and `eclipse_import_project`; leave it out of an RCP product.

## Connecting a client

The server writes a discovery file:

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

For Claude Code:

```bash
claude mcp add --transport http eclipse http://127.0.0.1:8642/mcp \
  --header "Authorization: Bearer $(jq -r .token <workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json)"
```

The transport is Streamable HTTP and every request carries `Authorization: Bearer <token>`; anything else gets a `401`.
The token lives in `~/.eclipse/com.vogella.eclipse.mcp.server/token` with owner-only permissions and survives restarts, updates and workspace switches.
With two IDEs open on the same fixed port, only the one that bound the port can serve it; in ephemeral-port mode each may use a different port.
The `workspace` field in the discovery file tells which workspace an endpoint serves.
After `eclipse_restart`, compare `startedAt` to know the new server is up; some clients need a manual reconnect, in Claude Code through `/mcp`.

Several clients can share one server.
While more than one is connected, `eclipse_get_build_status`, `eclipse_get_test_results`, `eclipse_get_provisioning_status` and `eclipse_get_command_output` refuse to guess "the most recent" entry, so pass the id explicitly.

The server offers tools only: `initialize`, `ping`, `tools/list` and `tools/call`.
Every tool returns pretty-printed JSON, and every list-returning tool honours `maxResults` and reports `total` and `truncated`.

## Testing Eclipse and SWT changes

The UI tools address widgets by their place in the SWT hierarchy rather than by screen coordinates, so a check survives a resize, a theme and another machine:

1. `eclipse_list_ui_targets` names the shells and parts; `eclipse_get_widget_tree` lists the widgets under one with a path for each, bounds in three coordinate systems, CSS id and class, and with `includeMetrics` what the control computes for itself: preferred size, trim, font and item height.
2. `eclipse_press_widget`, `eclipse_set_widget_text`, `eclipse_select_tab`, `eclipse_expand_row`, `eclipse_click`, `eclipse_drag`, `eclipse_scroll`, `eclipse_press_key` and `eclipse_type_text` act on a path; key and click fall back to SWT events where the window system refuses synthetic input.
3. `eclipse_wait_for` waits for a shell, widget, selection, editor or job condition, and `eclipse_wait_until_settled` for the display queue, the jobs and the reconcilers.
4. `eclipse_screenshot` captures a part, a shell, a widget, a screen region or the display; `method: print` paints the widgets instead of reading the screen.
5. `eclipse_mark_log` and then `eclipse_get_log_entries` show what an action logged, UI freezes included.

To run modified SWT or platform code, import the bundle's project into the IDE's workspace, build it, run `eclipse_substitute_bundle` with `action: substitute` and then `eclipse_restart`; `action: restore` and another restart put the installed bundle back.
For SWT, import the fragment for your platform, such as `binaries/org.eclipse.swt.gtk.linux.x86_64`, from a checkout whose native libraries match the Java code: a checkout without Git LFS has pointer files in their place, which the substitution refuses.
`eclipse_hot_code_replace` redefines changed classes without a restart where the change allows it.

## Tools

Tools marked ✎ change something; the rest are read-only.

**Workspace and files**

| Tool | Purpose |
|---|---|
| `eclipse_list_projects` | Projects with natures and open state |
| `eclipse_resolve_path` | Maps project names, workspace paths and absolute paths to each other and to the git repository |
| `eclipse_read_file`, `eclipse_read_working_copy`, `eclipse_search_text`, `eclipse_find_resources` | Read files as saved or with the unsaved changes of their working copy, search their text, find files by name in workspace, target platform and installation |
| `eclipse_read_image` | Return a PNG, JPEG, GIF or WebP from a project as an image the model can see |
| ✎ `eclipse_write_file`, `eclipse_edit_file` | Write a file or replace a passage, keeping the old content in local history |
| ✎ `eclipse_edit_working_copy` | Replace a passage in the unsaved text of an open editor, without saving |
| `eclipse_get_local_history`, ✎ `eclipse_restore_local_history` | List, read and restore the versions Eclipse keeps of a file, including a deleted one |
| ✎ `eclipse_delete` | Delete a source file, reporting registry and manifest entries that would dangle |
| ✎ `eclipse_refresh`, `eclipse_save_workspace` | Pick up external changes, save the workspace |
| ✎ `eclipse_set_project_state`, `eclipse_import_project`, `eclipse_remove_project` | Open, close, import and remove projects |
| `eclipse_get_project_dependencies`, `eclipse_get_classpath` | Project references and the resolved build path |

**Problems, builds and logs**

| Tool | Purpose |
|---|---|
| `eclipse_get_problems`, `eclipse_mark_problems` | Compiler errors and warnings, and a baseline to compare against |
| ✎ `eclipse_build`, `eclipse_cancel_build`, `eclipse_get_build_status` | Run builders as a job and poll the result |
| `eclipse_wait_until_quiet`, `eclipse_pause` | Wait for builds and refreshes, or for a fixed time |
| `eclipse_get_log_entries`, `eclipse_mark_log` | Read the Error Log, optionally from a marker |
| ✎ `eclipse_clear_log`, `eclipse_log_status` | Empty the Error Log, write an entry into it |
| `eclipse_get_preferences`, ✎ `eclipse_set_preference` | Read and write preferences, with the scope each value comes from |
| ✎ `eclipse_run_command`, `eclipse_get_command_output` | Run a shell command in a named directory and poll its output |

**Java**

| Tool | Purpose |
|---|---|
| `eclipse_find_references` | References to a type or member, single or batched as counts |
| `eclipse_list_declarations` | Declarations cross-checked against the extension registry and e4 models, for dead code sweeps |
| `eclipse_get_call_hierarchy`, `eclipse_get_type_hierarchy`, `eclipse_find_implementations` | Callers and callees, supertypes and subtypes, the classes and methods implementing an interface, abstract class or method |
| `eclipse_get_source`, `eclipse_get_outline`, `eclipse_search_types` | Source and Javadoc of any type or single member on the classpath, a type's members with signatures and line ranges, type search by name |
| ✎ `eclipse_rename` | Rename through the JDT refactoring |
| ✎ `eclipse_clean_up`, `eclipse_remove_unused_imports`, `eclipse_organize_imports`, `eclipse_format` | JDT clean-ups, import handling and formatting |
| `eclipse_get_quick_fixes`, ✎ `eclipse_apply_quick_fix` | A file's compiler problems with JDT's quick fixes for each, and applying one, without JDT UI |
| ✎ `eclipse_set_java_version` | Set a project's compiler level and JDK |
| ✎ `eclipse_run_tests`, `eclipse_get_test_results` | Run JUnit tests with optional VM arguments and read the results |
| ✎ `eclipse_hot_code_replace` | Redefine classes in the running IDE |

**Plug-in development**

| Tool | Purpose |
|---|---|
| `eclipse_get_bundle_info`, `eclipse_analyze_dependencies` | Bundles as PDE resolved them, declared versus used dependencies |
| ✎ `eclipse_edit_manifest`, `eclipse_set_bree` | Edit manifest headers, set the execution environment |
| `eclipse_get_target_platform`, ✎ `eclipse_set_target_platform` | Read and set the active target platform |
| ✎ `eclipse_install_bundle`, `eclipse_substitute_bundle` | Install a bundle into the live framework, or run a workspace bundle in place of the installed one |

**Debugging and profiling**

| Tool | Purpose |
|---|---|
| `eclipse_list_breakpoints`, ✎ `eclipse_set_breakpoint` | Read and edit breakpoints |
| `eclipse_list_launch_configurations`, ✎ `eclipse_debug_launch` | List and start launches |
| `eclipse_debug_status`, `eclipse_debug_get_frames` | Sessions, threads, frames and variables |
| ✎ `eclipse_debug_evaluate`, `eclipse_debug_control` | Evaluate expressions, step, resume and terminate |

**Git**

| Tool | Purpose |
|---|---|
| `eclipse_get_git_status` | Repository state through EGit |
| ✎ `eclipse_add_git_repository`, `eclipse_checkout`, `eclipse_fetch_pull_request` | Register a repository, switch branches, fetch a GitHub pull request |
| ✎ `eclipse_revert_files` | Revert files to HEAD, keeping the discarded content in local history |

**Gradle**

Needs Buildship; without it the tools say so.

| Tool | Purpose |
|---|---|
| ✎ `eclipse_refresh_gradle_project` | Synchronize a project with its build scripts, reporting what Buildship found |
| ✎ `eclipse_run_gradle_tasks`, `eclipse_get_gradle_run` | Run tasks as a job through Buildship, then poll, read the output or cancel |
| `eclipse_list_gradle_tasks` | The tasks of a project and its subprojects, from Buildship's Gradle model |

**Workbench and UI**

| Tool | Purpose |
|---|---|
| `eclipse_get_editor_context`, `eclipse_list_editors`, ✎ `eclipse_close_editor` | Active editor, cursor and selection, open editors |
| ✎ `eclipse_open`, `eclipse_open_compare` | Open a file or element, open a compare editor |
| `eclipse_get_selection`, ✎ `eclipse_set_selection` | Read and set the selection |
| ✎ `eclipse_type_text`, `eclipse_press_key`, `eclipse_set_widget_text`, `eclipse_press_widget`, `eclipse_set_sash`, `eclipse_click`, `eclipse_drag`, `eclipse_scroll`, `eclipse_double_click_text`, `eclipse_expand_row`, `eclipse_select_tab`, `eclipse_combo_list` | Drive editors and widgets |
| `eclipse_get_text_bounds`, `eclipse_list_annotations`, `eclipse_get_context_menu` | Text geometry, editor annotations, context menu contents |
| `eclipse_read_clipboard` | Read the text on the system clipboard |
| `eclipse_list_ui_targets`, `eclipse_get_widget_tree`, `eclipse_inspect_widget`, `eclipse_get_focus` | Shells, parts and widgets, with bounds, computed sizes, CSS styling and keyboard focus |
| ✎ `eclipse_dismiss_dialog` | Answer an open dialog |
| ✎ `eclipse_reset_dialog_settings` | Forget the answers wizards and dialogs remembered |
| `eclipse_list_commands`, ✎ `eclipse_run_workbench_command` | List and run workbench commands |
| ✎ `eclipse_run_launch_shortcut` | Invoke a Run As or Debug As shortcut on workspace resources (dry run by default), reporting the configurations it created and the launches it started |
| ✎ `eclipse_select_menu_item` | Pick a main menu or context menu entry by its labels, without a native menu appearing |
| ✎ `eclipse_show_view`, `eclipse_hide_view`, `eclipse_move_part`, `eclipse_set_part_state` | Arrange views |
| `eclipse_list_perspectives`, ✎ `eclipse_switch_perspective`, `eclipse_reset_perspective` | Perspectives |
| ✎ `eclipse_manage_window`, `eclipse_set_shell_bounds`, `eclipse_set_ide_visibility`, `eclipse_set_model_visibility` | Windows, their bounds, and what of the IDE is visible |
| `eclipse_list_themes`, `eclipse_list_theme_definitions`, ✎ `eclipse_set_theme`, `eclipse_register_theme`, `eclipse_apply_css` | Themes and live CSS |
| `eclipse_get_display_info`, `eclipse_screenshot` | Display scaling and PNG captures |
| `eclipse_wait_until_settled` | Wait until the UI has stopped changing |
| `eclipse_wait_for` | Wait until a shell, widget, selection, editor or job condition holds |

**Web pages**

| Tool | Purpose |
|---|---|
| ✎ `eclipse_web_open` | Load a URL in a browser the IDE drives, starting an installed Chrome, Chromium or Edge headless on first use, or a tab in one already running with `--remote-debugging-port` |
| `eclipse_web_snapshot` | The page as an outline of roles, names, states and refs |
| ✎ `eclipse_web_click`, `eclipse_web_type` | Click, type into a field or pick an option, then wait until the page settled |
| `eclipse_web_screenshot` | PNG of the viewport, the whole page or one element |
| ✎ `eclipse_web_close` | Close the page and the browser the IDE started |

The web tools talk to the browser over the Chrome DevTools Protocol with the JDK's own HTTP client, so they add no library and download nothing.
They look for Chrome, Chromium or Edge in the usual install locations, on `PATH` and in a Playwright browser cache; `-Dcom.vogella.eclipse.mcp.web.browser=<executable>` or the `browserPath` argument names another.
A browser the IDE starts gets a fresh temporary profile, deleted when it closes or the IDE exits.
In a container where Chromium cannot set up its sandbox, `-Dcom.vogella.eclipse.mcp.web.noSandbox=true` starts it without; as root that happens anyway.

**Installation**

| Tool | Purpose |
|---|---|
| `eclipse_get_installation` | Product, installed features and revertable configurations |
| ✎ `eclipse_add_repository`, `eclipse_remove_repository` | Manage update sites |
| `eclipse_check_for_updates`, ✎ `eclipse_update`, `eclipse_install`, `eclipse_uninstall`, `eclipse_get_provisioning_status` | Update, install and uninstall through p2 |
| ✎ `eclipse_restart`, `eclipse_exit` | Restart or shut down the IDE |

## Contributing a tool

Tools are contributed through the `com.vogella.eclipse.mcp.core.tools` extension point:

```xml
<extension point="com.vogella.eclipse.mcp.core.tools">
   <tool class="com.example.MyTool"/>
</extension>
```

`com.example.MyTool` implements `com.vogella.eclipse.mcp.core.IMcpTool`.
It is called on a worker thread, must not open a dialog, must say in its description if it changes anything, and must finish within the call timeout or hand back a handle to poll.
A bundle can replace a tool of the same name with a richer version by contributing it with a higher `priority`, as in `<tool class="com.example.BetterScreenshot" priority="1"/>`; the highest priority wins while both bundles are installed.
See `AGENTS.md` for the full set of rules.

## Describing a product

A product that embeds the server tells clients what they are connected to through the `com.vogella.eclipse.mcp.core.server` extension point:

```xml
<extension point="com.vogella.eclipse.mcp.core.server">
   <serverInfo name="com.example.product"/>
   <instructions replace="true">Example Product: call example_login_state first; order ids look like ORD-123.</instructions>
</extension>
```

Clients put the instructions into the agent's context.
Contributed texts are appended to the default instructions, which describe the IDE, and `replace="true"` leaves the default out.
`serverInfo` sets the name and version reported on `initialize`; the version defaults to the contributing bundle's.

## Bundles

| Bundle | Contains |
|---|---|
| `com.vogella.eclipse.mcp.core` | Provider-independent tool API, registry, extension point and shared infrastructure; no MCP, Jetty or UI dependency |
| `com.vogella.eclipse.mcp.basic` | General-purpose workspace, file, build, preference, log, command and other basic tools; provider depending on core |
| `com.vogella.eclipse.mcp.server` | MCP protocol, embedded Jetty, bearer token and discovery file |
| `com.vogella.eclipse.mcp.ui` | Workbench tools, screenshots, widget inspection, preference page |
| `com.vogella.eclipse.mcp.jdt` | Java model, refactoring, clean-up and test tools |
| `com.vogella.eclipse.mcp.debug` | Breakpoint and debug session tools |
| `com.vogella.eclipse.mcp.pde` | Plug-in development tools |
| `com.vogella.eclipse.mcp.git` | EGit tools |
| `com.vogella.eclipse.mcp.gradle` | Gradle tools through Buildship |
| `com.vogella.eclipse.mcp.ide` | IDE-only tools: open in editor, compare, launch shortcuts, project import |
| `com.vogella.eclipse.mcp.p2` | Provisioning tools |
| `com.vogella.eclipse.mcp.web` | Web page tools over the Chrome DevTools Protocol; needs only core and an installed Chromium based browser |

## Commercial support

[vogella GmbH](https://vogella.com/services/) offers training and consulting around Eclipse and its tooling, and AI based Java cleanup [for legacy code and code optimization](https://vogella.com/services/).
Issues and pull requests are welcome here; the commercial route exists for work that wants a schedule attached.
