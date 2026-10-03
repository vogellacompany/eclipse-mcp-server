<img src="docs/images/logo256.png" alt="" width="128" align="right">

# Eclipse MCP Server

Turns a running Eclipse IDE into an [MCP](https://modelcontextprotocol.io) server, so that external LLM clients (Claude Code, Cursor, any MCP-capable agent) can ask the IDE for what they cannot cheaply reconstruct from files: the resolved Java model, the builder's problem markers, the debugger and the live workbench.

Built and maintained by [vogella GmbH](https://vogella.com/services/), and used in our consulting and in our AI based Java cleanup services [for legacy code and code optimization](https://vogella.com/services/).

The server is **disabled by default**, listens on `127.0.0.1` only and rejects every request without the bearer token.
Most tools are read-only; every tool that changes something says so in its own description, and those that can default to a dry run do.

<p align="center">
  <img src="docs/images/splash-animated.webp" alt="The Eclipse MCP Server splash screen" width="600">
</p>

Optionally the IDE can come up under this splash: set `replaceSplash` in the installation's configuration scope, and it takes effect at the restart after the one that applies it.

## Installing

In Eclipse, choose *Help > Install New Software*, add

```
https://vogellacompany.github.io/eclipse-mcp-server/
```

and install the **Eclipse MCP Server** feature.
The site carries the newest build only; older versions are available as the repository zip attached to each GitHub release, through *Add > Archive*.

## Building

Requires JDK 25 and Maven 3.9 or newer.

```bash
mvn clean verify
```

The p2 repository ends up in `update-site/com.vogella.eclipse.mcp.repository/target/repository`.
To work on the code in Eclipse, import the projects with *Search for nested projects* enabled and set `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` as the active target platform.

Every push to `main` publishes to the update site; pushing a `v<version>` tag additionally creates a GitHub release with the repository zip.

## Enabling the server

*Preferences > General > MCP Server*:

* **Enable MCP server**, off by default
* **Port**, `8642` by default
* **Tool call timeout**, `30` seconds by default

Changes apply immediately.
The page also shows the URL, the bearer token and the discovery file, and can regenerate the token.
If the port is taken, the server stays down and the page says why; it never moves to another port behind a client's back.

### In an RCP application

The server starts through a declarative service, so an RCP application needs `org.apache.felix.scr` and a workspace, but no `org.eclipse.ui.startup`.
Install `com.vogella.eclipse.mcp.core`, `com.vogella.eclipse.mcp.server` and the third party bundles the feature lists; the other bundles are optional and each only adds its own tools.
`com.vogella.eclipse.mcp.ui` also resolves in a pure E4 application, which then gets the widget, screenshot, keyboard, dialog and settle tools, with parts named by their model element id; the tools built on editors, views, perspectives and workbench commands need the 3.x workbench and say so.
Without the ui bundle there is no preference page, so enable the server in `plugin_customization.ini`:

```
com.vogella.eclipse.mcp.server/enabled=true
com.vogella.eclipse.mcp.server/port=8642
```

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
The token lives in `~/.eclipse/com.vogella.eclipse.mcp.server/token` with owner-only permissions, and survives restarts, updates and workspace switches, so a client is configured once.
With two IDEs open, the first one owns the port; `workspace` in the discovery file tells which one a client reached.
After `eclipse_restart`, compare `startedAt` to know the new server is up; some clients need a manual reconnect, in Claude Code through `/mcp`.

Several clients can share one server.
While more than one is connected, `eclipse_get_build_status`, `eclipse_get_test_results`, `eclipse_stop_sampling` and `eclipse_get_provisioning_status` refuse to guess "the most recent" entry, so pass the id explicitly.

The server offers tools only: `initialize`, `ping`, `tools/list` and `tools/call`.
Every tool returns pretty-printed JSON, and every list-returning tool honours `maxResults` and reports `total` and `truncated`.

## Scripting the IDE from outside

`eclipse_run_script` runs a sequence of tools with assertions in one call, and two scripts in `releng` make that runnable from a build:

```bash
# against the IDE you are working in
releng/mcp-script.py releng/scripts/content-assist.json

# against a throwaway IDE, on a workspace and a port of its own
releng/mcp-test-ide.sh --ide /path/to/eclipse --junit results.xml releng/scripts/smoke.json
```

`mcp-script.py` prints each step, exits non-zero on a failed expectation and can write JUnit XML.
`mcp-test-ide.sh` starts the IDE on a fresh workspace and configuration area, sharing the installation's plug-ins but not any bundle substituted into it, runs the scripts and shuts the IDE down again.

## Tools

Tools marked ✎ change something; the rest are read-only.

**Workspace and files**

| Tool | Purpose |
|---|---|
| `eclipse_list_projects` | Projects with natures and open state |
| `eclipse_resolve_path` | Maps project names, workspace paths and absolute paths to each other and to the git repository |
| `eclipse_read_file`, `eclipse_search_text`, `eclipse_find_resources` | Read files, search their text, find files by name in workspace, target platform and installation |
| `eclipse_read_image` | Return a PNG, JPEG, GIF or WebP from a project as an image the model can see |
| ✎ `eclipse_write_file`, `eclipse_edit_file` | Write a file or replace a passage, keeping the old content in local history |
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
| `eclipse_get_call_hierarchy`, `eclipse_get_type_hierarchy` | Callers, supertypes and subtypes |
| `eclipse_get_source`, `eclipse_search_types` | Source and Javadoc of any type on the classpath, type search by name |
| ✎ `eclipse_rename` | Rename through the JDT refactoring |
| ✎ `eclipse_clean_up`, `eclipse_remove_unused_imports`, `eclipse_organize_imports`, `eclipse_format` | JDT clean-ups, import handling and formatting |
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
| `eclipse_start_sampling`, `eclipse_stop_sampling`, `eclipse_show_trace` | Sample thread stacks to profile or diagnose a freeze, and render them as a flame graph |
| `eclipse_start_flight_recording`, `eclipse_stop_flight_recording` | Record the IDE's JVM with Java Flight Recorder |

**Git**

| Tool | Purpose |
|---|---|
| `eclipse_get_git_status` | Repository state through EGit |
| ✎ `eclipse_add_git_repository`, `eclipse_checkout`, `eclipse_fetch_pull_request` | Register a repository, switch branches, fetch a GitHub pull request |
| ✎ `eclipse_revert_files` | Revert files to HEAD, keeping the discarded content in local history |

**Workbench and UI**

| Tool | Purpose |
|---|---|
| `eclipse_get_editor_context`, `eclipse_list_editors`, ✎ `eclipse_close_editor` | Active editor, cursor and selection, open editors |
| ✎ `eclipse_open`, `eclipse_open_compare` | Open a file or element, open a compare editor |
| `eclipse_get_selection`, ✎ `eclipse_set_selection` | Read and set the selection |
| ✎ `eclipse_type_text`, `eclipse_press_key`, `eclipse_set_widget_text`, `eclipse_press_widget`, `eclipse_click`, `eclipse_double_click_text`, `eclipse_expand_row`, `eclipse_select_tab` | Drive editors and widgets |
| `eclipse_get_text_bounds`, `eclipse_list_annotations`, `eclipse_get_context_menu` | Text geometry, editor annotations, context menu contents |
| `eclipse_list_ui_targets`, `eclipse_get_widget_tree`, `eclipse_inspect_widget` | Shells, parts and widgets, with bounds and CSS styling |
| ✎ `eclipse_dismiss_dialog` | Answer an open dialog |
| `eclipse_list_commands`, ✎ `eclipse_run_workbench_command` | List and run workbench commands |
| ✎ `eclipse_run_launch_shortcut` | Invoke a Run As or Debug As shortcut on workspace resources (dry run by default), reporting the configurations it created and the launches it started |
| ✎ `eclipse_select_menu_item` | Pick a main menu or context menu entry by its labels, without a native menu appearing |
| ✎ `eclipse_show_view`, `eclipse_hide_view`, `eclipse_move_part`, `eclipse_set_part_state` | Arrange views |
| `eclipse_list_perspectives`, ✎ `eclipse_switch_perspective`, `eclipse_reset_perspective` | Perspectives |
| ✎ `eclipse_manage_window`, `eclipse_set_shell_bounds`, `eclipse_set_ide_visibility`, `eclipse_set_model_visibility` | Windows, their bounds, and what of the IDE is visible |
| `eclipse_list_themes`, `eclipse_list_theme_definitions`, ✎ `eclipse_set_theme`, `eclipse_register_theme`, `eclipse_apply_css` | Themes and live CSS |
| `eclipse_get_display_info`, `eclipse_screenshot`, `eclipse_start_screencast`, `eclipse_stop_screencast` | Display scaling, PNG captures and GIF recordings |
| `eclipse_wait_until_settled` | Wait until the UI has stopped changing |
| ✎ `eclipse_run_script` | Run several tools in sequence with expectations, optionally in one UI turn |

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
See `AGENTS.md` for the full set of rules.

## Bundles

| Bundle | Contains |
|---|---|
| `com.vogella.eclipse.mcp.core` | The tool API, registry and extension point, plus workspace, file, build, preference, log and command tools; no MCP, Jetty or UI dependency |
| `com.vogella.eclipse.mcp.server` | MCP protocol, embedded Jetty, bearer token and discovery file |
| `com.vogella.eclipse.mcp.ui` | Workbench tools, screenshots, widget inspection, preference page |
| `com.vogella.eclipse.mcp.jdt` | Java model, refactoring, clean-up and test tools |
| `com.vogella.eclipse.mcp.debug` | Breakpoint and debug session tools |
| `com.vogella.eclipse.mcp.pde` | Plug-in development tools |
| `com.vogella.eclipse.mcp.git` | EGit tools |
| `com.vogella.eclipse.mcp.p2` | Provisioning tools |

## Commercial support

[vogella GmbH](https://vogella.com/services/) offers training and consulting around Eclipse and its tooling, and AI based Java cleanup [for legacy code and code optimization](https://vogella.com/services/).
Issues and pull requests are welcome here; the commercial route exists for work that wants a schedule attached.
