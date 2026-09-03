# Eclipse platform bugs and API gaps

Defects and gaps in Eclipse itself that this project ran into, so that they are
not rediscovered or quietly worked around forever.
Each entry says what was observed, where it is, and whether anything is filed.

Add an entry whenever a workaround here exists because the platform is wrong,
rather than because we are.

## Open

### SWT: `Control.print` does not paint a `CTabFolder`'s `topRight` control

A print of a `CTabFolder` omits its `topRight` children, which in the workbench
are the view toolbar, the view menu and the minimise and maximise buttons.
Rooting the print at the folder, at its parent and at the window's content
composite all miss them, so no ancestor supplies them.

The tab row is also laid out as though the control were absent: for a stack
whose model reports `ToolbarComposite` at x=369, the printed tab label runs to
about x=460, which cannot happen on screen. So the folder paints itself without
its `topRight` rather than merely skipping a child.

Observed on GTK at zoom 200 through `eclipse_screenshot` with `includeToolbar`.
Nothing filed yet. The consequence for this project is that `includeToolbar`
cannot show a view toolbar; capture the shell and crop to bounds from
`eclipse_get_widget_tree` instead.

### p2: a failed resolution reports only "Operation details"

`ProfileChangeOperation.resolveModal` returns, for every planner failure, a
status whose top level message is the literal string `Operation details`; the
actual conflicts, one per unit, live only in the children of that multi status.
p2 also logs nothing itself, so the failure does not reach the platform log and
`eclipse_get_log_entries` shows nothing.

Observed when a feature installed at `1.0.0.202608261121` was rebuilt as
`1.0.0.202608261142`: every later install and update failed with no usable
diagnosis, and the pin was found by unzipping the installed feature by hand.

Worked around in `ResolutionStatuses`, which flattens the status tree into the
tool answer and logs the whole status once at warning level.
Not filed upstream as of 2026-08-26.

### PDE: `NullPointerException` in `DependencyManager.findRequirementsClosure`

`ui/org.eclipse.pde.core/.../DependencyManager.java:266`

```java
: namespaces.stream().map(wiring::getRequiredWires).flatMap(List::stream)::iterator;
```

`BundleWiring.getRequiredWires(String)` returns `null` when the wiring is no
longer in use, and `flatMap(List::stream)` over that null throws.
Line 254 has the same exposure for `HOST_NAMESPACE`, and the
`namespaces.isEmpty()` branch would fail at the loop instead of in the stream.

Reached from `IProject.getReferencedProjects()` through
`DynamicPluginProjectReferences`, so it fires from the IDE's own
`ProjectReferenceGraph` job and, more seriously, from build order computation.
Observed after a p2 update plus bulk project close and open in a 755 project
workspace.

Not filed upstream as of 2026-08-21; no matching issue in `eclipse-pde/eclipse.pde`.
Worked around in `GetProjectDependenciesTool` by catching and skipping, which
protects our call only.
`DynamicPluginProjectReferences` is registered unconditionally in `plugin.xml`,
so there is no preference to disable it.

### SWT: `Control.print` paints at the monitor's device scale

On a 200% monitor, `print` draws the widget at device resolution whatever the
target image is sized in, so drawing into an image created from the widget's
size in points writes a 2x picture into a 1x canvas and keeps the top left
quarter. Nothing reports it: the image is exactly the size a correct capture
would be.

Correcting it with a `GC` transform of `1/zoom` does not work either. It scales
the paint down into a canvas that is still device sized, so three quarters of
the image is then empty rather than three quarters of the widget missing. Both
states were shipped from here before the right one was found.

What works is `new Image(Device, ImageGcDrawer, int, int)` and reading the
result with `getImageData(zoom)`: the drawer receives a `GC` SWT has already set
up for the target zoom. Used in `ScreenshotTools`.

Not filed. Arguably intended behaviour of a low level API, but the combination
of "no way to ask what scale it will paint at" and "silent clipping" is what
makes it a trap.

### SWT: window capture silently returns a blank image

`bundles/org.eclipse.swt/Eclipse SWT/gtk/.../GC.java`, around line 489

```java
} else if (data.drawable != 0) {
    if (!GTK.GTK4) GDK.gdk_cairo_set_source_window(cairo, data.drawable, 0, 0);
}
```

On GTK4 the branch does nothing, so the caller gets a valid but empty image with
no error at all. Separately, under a compositing window manager the window
contents live in an offscreen pixmap, so reading the X11 root drawable through
XWayland also yields uniform pixels.

The failure is silent in both cases, which makes it the worst shape of bug for a
screenshot tool. `ScreenshotTools` detects it by rejecting a uniform capture and
falls back to `Control.print`.

### SWT: `Display.getBounds()` zoom is not the primary monitor's

[eclipse-platform/eclipse.platform.swt#3530](https://github.com/eclipse-platform/eclipse.platform.swt/issues/3530),
open, targeting 4.41, with PR #3532.

`Display.getBounds()` derives its zoom from `DPIUtil.getDeviceZoom()`, which
reports the zoom of whichever shell last changed zoom rather than the primary
monitor, so full display capture is non-deterministic on Windows with multiple
monitors at different DPI. Not reproducible on Linux; relevant when
`eclipse_screenshot` runs on Windows.

### p2: `ColocatedRepositoryTracker` refreshes the artifact manager too

`ColocatedRepositoryTracker.java:96-97` refreshes both the metadata and the
artifact repository manager. An update check never reads artifact metadata, so
a check through that path costs twice what it needs to.

Not filed. **This entry led this project astray and the conclusion below has
been corrected.** Refreshing metadata alone was adopted here on the strength of
it, and that is wrong for a composite whose release replaces the child rather
than adding one: see the next entry. The waste is real for a plain update check
and the saving is not worth having for an install.

### p2: a composite artifact repository is not refreshed with its metadata

**Observed, twice, self updating this server against its own update site.**
`eclipse_check_for_updates` refreshed only `IMetadataRepositoryManager`, saw the
newly published version and resolved against it. The install then failed in the
download phase:

```
No repository found containing: osgi.bundle,com.vogella.eclipse.mcp.core,0.2.0.202608231851
```

The cached composite ARTIFACT repository still pointed at `releases/<previous>/`,
which the publish had deleted, so no repository could supply the bundles. The
message names neither the cache nor the site, and the resolution succeeding
first makes it read as a broken publish.

Fixed here in `Provisioning.refreshArtifacts` by refreshing both managers. This
is the concrete case behind the entry above: skipping the artifact side is only
a saving for a check that never installs.

### p2: suspected stale child metadata after a composite refresh

Reported by the p2 session, **still unverified**, and distinct from the entry
above, which was the artifact side rather than the metadata side: a parent
composite may keep child instances loaded during its own construction and serve
the previous generation of child metadata.

Not seen here once both managers are refreshed.

## API gaps rather than defects

### JDT: no headless quick fix API

The concrete fixes live in `org.eclipse.jdt.internal.corext.fix`, exported
`x-friends:="org.eclipse.jdt.ui,..."`, so they are a compile error outside JDT UI.
`org.eclipse.jdt.core.manipulation` exports only the framework types
(`ICleanUpFixCore`, `CleanUpContextCore`, `CUCorrectionProposalCore`) and nothing
that maps a marker to proposals.

Consequence: `eclipse_get_quick_fixes` cannot be built headlessly. It would need
`org.eclipse.jdt.ui` and therefore the UI thread, which is wrong for bulk
application.

### JDT: `SearchMatch.getResource()` attributes a binary match to the wrong project

For a match inside a jar it returns the project that owns the classpath entry, so
the path is a bare project name with no file component. Arguably as designed, but
it means a naive caller reports references found in `org.eclipse.jdt.ui.jar` as
source references in whichever project depends on that jar.

Handled in `JavaModelSupport.describeLocation`, which reports an explicit
`origin` and puts the jar in `library`.

### JDT: manipulation layer needs a preference node id set by hand

`JavaManipulation.getPreference` calls `ProjectScope.getNode` with a null node id
when nothing set it, throwing `IllegalArgumentException` rather than returning a
default. Hit headlessly by both `OrganizeImportsTool` and `RenameTool`, the
latter only for field renames, through `GetterSetterUtil`.

Worked around by setting `JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui")`.

### Error Log view: no API and no command to clear what it shows

The view keeps the parsed log in memory and does not watch the file, so deleting
the file underneath it leaves it showing entries that are gone. Its own delete
action handles that by calling `LogView.handleClear()` right after
`fInputFile.delete()` (`LogView.doDeleteLog`), but there is no way for anyone
else to reach that: `LogView` lives in `org.eclipse.ui.internal.views.log`,
exported `x-friends:="org.eclipse.pde.ui"`, the clear is an anonymous `Action`
built in `createClearAction` rather than a command, and nothing is contributed
to the command framework. `IViewPart` offers nothing either.

`ErrorLogRefresh` calls `handleClear()` reflectively on the open view, which
works because the class and the method are public, and reports what it came to
rather than assuming it worked. A command id for "clear the Error Log view", or
a `handleClear` on a published interface, would remove the need.

### e4 CSS: a snippet cannot be applied through `IThemeEngine`

Applying an ad-hoc stylesheet needs `resetCurrentTheme()` and `getCSSEngines()`,
and both are on `org.eclipse.e4.ui.css.swt.internal.theme.ThemeEngine` rather
than on `IThemeEngine`, whose package is exported `x-friends` to the workbench
bundles.
PDE's own CSS scratch pad casts to the implementation and carries the comment
`FIXME: expose these new protocols: resetCurrentTheme() and getCSSEngines()`
(`ui/org.eclipse.pde.spy.css/.../CSSScratchPadPart.java`), so the gap is known
inside the platform and unfiled.

`CssStyling` reaches both reflectively and looks the engine up by name through
`IEclipseContext`, which keeps this bundle off the friends list entirely.

### e4 CSS: `CSSEngine.parseStyleSheet` changed its return type

`org.w3c.dom.stylesheets.StyleSheet` in 4.40, `CSSStyleSheetImpl` in 4.41. That
is source compatible and binary incompatible: a call site compiled against the
older interface fails with `NoSuchMethodError` on the newer engine, which is
exactly the situation of a plug-in built against a release target platform and
installed into a newer IDE.

The same change dropped `org.w3c.dom.css.CSSStyleSheet` from the returned type,
so a rule count has to be read as `getCssRules()` or as `getRules()` depending
on which engine is running.

Not filed; the package is `x-friends` and therefore provisional by convention,
though it is what every theme in the wild is styled by. `CssStyling.parse` calls
it reflectively and `CssStyling.rules` handles both shapes.

### e4 CSS: styling a preference block does not say what it wrote

`org.eclipse.e4.ui.css.swt.properties.preference.EclipsePreferencesHandler.overrideProperty`
writes the value only when the key is unset or when
`EclipsePreferencesHelper.isThemeChanged()` answers true, and that flag is false
after any start-up that restored the theme it saved, because then previous and
current theme id are equal. A block applied with no theme change in between
therefore overrides only the `DefaultScope` value while an existing instance
value keeps winning, `applyCSSProperty` still returns true either way, and
nothing in the return value or the engine distinguishes the two outcomes.

Not filed; refusing to clobber a user preference until a real theme change has
happened is arguably intended, but the silence is not.
`CssStyling.stylePreferences` works around it by reading every declared key
back after the call and reporting the ones that kept their value as unchanged,
rather than trusting the return.

The selector syntax has the same shape of problem on the way in:
`ThemeElementDefinitionHelper.escapeId` replaces dots with dashes and there is
no inverse that survives a qualifier which legitimately contains a dash.
A wrongly unescaped qualifier matches no rules at all, so the read-back decides
here too.

### e4 CSS: themes contributed at runtime never reach the engine

`ThemeEngine`'s constructor walks the extension registry once and stores the
result in a private final list. There is no registry field, no listener field
and no `IRegistryEventListener` on the class, so a theme bundle installed into a
running framework contributes to `org.eclipse.e4.ui.css.swt.theme` and
`getThemes()` still does not see it until the next start.

Verified against `org.eclipse.e4.ui.css.swt.theme_0.15.100.v20260422-0926.jar`,
the version the IDE on this machine runs, with `javap`.

Not filed; the engine has always worked this way rather than regressed, but the
gap is invisible: p2 reports the install as successful and the theme is simply
absent until a restart.
`eclipse_register_theme` works around it by registering the stylesheet through
`IThemeEngine.registerTheme(String, String, String)`, which the public interface
declares; the four argument overload with the os version match exists only on
the internal class and is deliberately not used.

Related, and measured at the same time: `setTheme` handed an id that matches
nothing loops the themes, falls through, logs a warning through `ILog` and
leaves the current theme alone. It does not throw and corrupts nothing, but the
warning goes where an MCP caller cannot read it, which is why
`eclipse_set_theme` refuses unregistered ids itself instead of forwarding them.

## `LineNumberRulerColumn` paints from a range the widget has already moved past

Observed 2026-08-29 on 4.41 (`org.eclipse.jface.text.source_3.31.100.v20260814-0956`, stock): three `IllegalArgumentException: Index out of bounds` logged as "Unhandled event loop exception", thrown out of `LineNumberRulerColumn.doubleBufferPaint`.

`doubleBufferPaint` captures the `ILineRange visibleLines` at line 695 and then hands it to an `ImageGcDrawer` at 709-714.
SWT invokes that drawer lazily rather than at construction: the stack shows the callback firing from inside `Image.getImageData` during `GC.drawImage` (`GC.java:810` to `916`, `Image.java:1148`, `1267`, `1286`), one frame below `doubleBufferPaint:705`.
By then the widget can have scrolled or shrunk, `JFaceTextUtil.modelLineToWidgetLine` yields a widget line the `StyledText` no longer has, and `doPaint:868` `getOffsetAtLine` raises `SWT.ERROR_INVALID_RANGE`.

Here it surfaced during shutdown, inside the nested event loop of `SaveableHelper.waitForBackgroundSaveJobs`, which widens the window between the capture and the callback.
Four independent stack frames match the shipped source exactly (616, 705, 712, 868), and no `org.eclipse.jface.text` has ever been substituted in this installation, so it is not a local artifact.

This is the same shape as the `Control.print` HiDPI problem below: an `ImageGcDrawer` callback runs later than its caller assumes.
Nothing filed upstream yet; the session working in the platform text editors has the analysis.

## `Control.print` on a HiDPI monitor doubles a composed capture, so `includeToolbar` is unreliable there

Observed 2026-08-29 on GTK3 at 200 % zoom: `eclipse_screenshot` with `includeToolbar` (the part stack, an e4 `CTabFolder`) renders the editor content at twice its size, so a highlight from `eclipse_get_text_bounds` `inPartStack` lands several lines off.
A single top-level `Control.print` of one part is correct at any zoom, which is why a `part` capture (no `includeToolbar`) is pixel-exact and why `inPart` bounds enclose the text they name.
Composing a capture from several prints, whether by printing the `CTabFolder` for its children or by printing each child into a sub-image and drawing it in, doubles: the print GC an `ImageGcDrawer` hands out already carries the monitor's 2x transform and the child print applies it again.
Attempts to compose the stack (folder chrome plus per-child prints, with and without a translate) all reproduced the doubling and were reverted.

For now `eclipse_screenshot` on a HiDPI monitor is trustworthy for `target: part` and `target: display`; a `part` capture already contains the whole `SourceViewer`, meaning the vertical ruler, the line numbers, the overview ruler, the squiggles and the caret, which is what a caller documenting editor drawing needs.
`includeToolbar` and a `shell` capture keep the 2x limitation until the print path is understood.
Nothing filed upstream yet.

## `SmartImportJob` leaves auto-build switched off when an import fails

Observed 2026-08-31 on 4.42 while replacing `eclipse_import_project` with the platform's own smart import (`org.eclipse.ui.ide`, `org.eclipse.ui.internal.wizards.datatransfer.SmartImportJob`).

`run(IProgressMonitor)` reads `workspace.isAutoBuilding()`, switches auto-build off, and switches it back on near the end of the same `try` block.
The `catch (Exception ex)` below that returns an error status without restoring it, and there is no `finally` anywhere in the file.
So any exception in between, a `CoreException` out of project creation or anything a third party `ProjectConfigurator` throws, returns a failed import and leaves the workspace with auto-build off, silently and for good.
`ImportProjectTool` restores what it found in its own `finally` rather than trusting the job.

The same class also calls `PlatformUI.getWorkbench().getWorkingSetManager().addToWorkingSets(res, this.workingSets)` unconditionally in `toExistingOrNewProject`, on every project it creates, without checking whether any working set was asked for.
Adding to zero working sets is a no-op, but the call still forces `PlatformUI.getWorkbench()`, so the class cannot run without a workbench even when no working set is involved.
That is why this tool lives in the ui bundle and refuses headlessly instead of being testable in the core test run.

Not filed upstream yet; a task description for both was handed to a session working in `eclipse.platform.ui`.

## `Control.print` on GTK 3 leaves the printed widget unpainted on screen

Observed 2026-09-03 on GTK 3.24 under Xvfb, reported by a session recording a demo headless: after `eclipse_start_screencast` had printed a PDE feature editor once, a root capture of that editor was 3 KB of section backgrounds where the capture a second earlier was 26 KB of tables, headings and buttons, and it stayed that way after the recording stopped until a tab switch forced a repaint.
The frames themselves were fine.

`Control.print` on GTK 3 (`org.eclipse.swt.widgets.Control`, the `else` branch of `print(GC)`) size-allocates the live top handle to satisfy `gtk_widget_draw`'s precondition and then draws it into the caller's cairo context; the on-screen copy is not invalidated afterwards, and under a window system with no compositor it shows whatever was left.
`Screencast.paint` and the `Paintable` prints in `ScreenshotTools` queue `Control.redraw(0, 0, w, h, true)` after every print on GTK, which was verified to keep the root capture identical before, during and after a recording.
Nothing filed upstream yet.

## A hot bundle refresh leaves the registry cache stale, and the next start restores editors that throw

Observed 2026-09-03 on 4.42 in the Xvfb test IDE: after `eclipse_install_bundle` replaced `org.eclipse.pde.core` in the live framework (refreshing 22 bundles, `org.eclipse.pde.ui` among them) and the IDE was then restarted normally, every editor the workbench restored from the previous session, a PDE manifest editor and a feature editor, failed to open or close with `InvalidRegistryObjectException: Invalid registry object`, while an editor opened fresh in the same session worked.
Restarting with `-clean` made the same restored editors work.
The extension registry cache written at shutdown evidently held objects from the refreshed contributor, and the restored editor references resolved their descriptors against it.
`eclipse_restart` therefore adds `-clean` by default after a hot install or a substitution, and reports it; the startup costs a few seconds more and the registry and resolver caches are rebuilt.
Nothing filed upstream yet.
