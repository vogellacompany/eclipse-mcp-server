package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Forgets the answers wizards and dialogs remembered, in memory and in each
 * bundle's {@code dialog_settings.xml}, so the next one opens with its defaults.
 */
public final class ResetDialogSettingsTool implements IMcpTool {

	private static final String FILE_NAME = "dialog_settings.xml"; //$NON-NLS-1$

	private static final String PROVIDER_BUNDLE = "org.eclipse.e4.ui.workbench.swt"; //$NON-NLS-1$

	private static final String PROVIDER_SERVICE = "org.eclipse.e4.ui.internal.workbench.swt.DialogSettingsProviderService"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_reset_dialog_settings"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "RESETS the answers wizards and dialogs remembered (IDialogSettings), such as the last choices in PDE's New Plug-in Project wizard, so the next wizard opens with its defaults without restarting the IDE. Defaults to a dry run that lists what would be reset. For each bundle it DELETES <workspace>/.metadata/.plugins/<bundle>/dialog_settings.xml and reloads the in-memory settings, which then start empty. Without bundles it acts on every installed bundle that has settings in memory or on disk; onDisk and inMemory say which of the two each bundle had, and reload says whether its in-memory settings are reloaded. A named bundle with neither is listed under nothingToReset; when the platform's list of loaded settings cannot be read (inMemoryKnown false) only bundles with a file are reloaded. Modal dialogs open at the time are listed under openDialogs, since they save the old answers back when they close."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "bundles": {"type":"array","items":{"type":"string"},"description":"Bundle symbolic names to reset, for example org.eclipse.pde.ui. Default: every installed bundle with dialog settings in memory or on disk."},
				    "dryRun": {"type":"boolean","default":true,"description":"Only report what would be reset."},
				    "maxResults": {"type":"integer","minimum":1,"default":200}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, Integer.MAX_VALUE); //$NON-NLS-1$
		Path pluginStateArea = pluginStateArea();
		if (pluginStateArea == null) {
			return McpToolResult.error("The IDE has no workspace, so there are no dialog settings to reset."); //$NON-NLS-1$
		}
		Object requestedBundles = arguments == null ? null : arguments.get("bundles"); //$NON-NLS-1$
		if (requestedBundles != null && !(requestedBundles instanceof List<?>)) {
			return McpToolResult.error("bundles must be an array of bundle symbolic names."); //$NON-NLS-1$
		}
		Set<Bundle> loaded = loadedBundles();

		Map<String, Bundle> candidates = new TreeMap<>();
		List<String> unknown = new ArrayList<>();
		if (requestedBundles instanceof List<?> requested) {
			for (Object item : requested) {
				if (!(item instanceof String name) || name.isBlank()) {
					return McpToolResult.error("bundles must be an array of bundle symbolic names."); //$NON-NLS-1$
				}
				name = name.trim();
				Bundle bundle = Platform.getBundle(name);
				if (bundle == null) {
					unknown.add(name);
				} else {
					candidates.put(bundle.getSymbolicName(), bundle);
				}
			}
		} else {
			if (loaded != null) {
				loaded.forEach(b -> candidates.put(b.getSymbolicName(), b));
			}
			try (Stream<Path> dirs = Files.list(pluginStateArea)) {
				dirs.filter(d -> Files.isRegularFile(d.resolve(FILE_NAME))).forEach(d -> {
					Bundle bundle = Platform.getBundle(d.getFileName().toString());
					if (bundle != null) {
						candidates.put(bundle.getSymbolicName(), bundle);
					}
				});
			} catch (IOException e) {
				// no plug-in state yet, so nothing on disk to reset
			}
		}

		JsonArray reset = new JsonArray();
		List<String> nothingToReset = new ArrayList<>();
		List<JsonObject> failures = new ArrayList<>();
		int total = 0;
		for (Bundle bundle : candidates.values()) {
			Path file = pluginStateArea.resolve(bundle.getSymbolicName()).resolve(FILE_NAME);
			boolean onDisk = Files.isRegularFile(file);
			// without the provider map only a file proves the settings were used; reloading creates a provider
			boolean inMemory = loaded == null ? onDisk : loaded.contains(bundle);
			if (!onDisk && !inMemory) {
				nothingToReset.add(bundle.getSymbolicName());
				continue;
			}
			JsonObject entry = new JsonObject().put("bundle", bundle.getSymbolicName()).put("onDisk", onDisk); //$NON-NLS-1$ //$NON-NLS-2$
			if (loaded != null) {
				entry.put("inMemory", inMemory); //$NON-NLS-1$
			}
			entry.put("reload", inMemory); //$NON-NLS-1$
			if (!dryRun) {
				String step = "delete"; //$NON-NLS-1$
				try {
					// the file goes first, or the reload reads the old answers back
					Files.deleteIfExists(file);
					step = "reload"; //$NON-NLS-1$
					if (inMemory) {
						PlatformUI.getDialogSettingsProvider(bundle).loadDialogSettings();
					}
				} catch (IOException | RuntimeException | LinkageError e) {
					// after a failed reload the old answers are still in memory and come back on the next save
					failures.add(entry.put("failedAt", step).put("fileDeleted", !"delete".equals(step)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("error", String.valueOf(e))); //$NON-NLS-1$
					continue;
				}
			}
			total++;
			if (total <= maxResults) {
				reset.add(entry);
			}
		}

		boolean truncated = total > maxResults || unknown.size() > maxResults || nothingToReset.size() > maxResults
				|| failures.size() > maxResults;
		JsonObject result = new JsonObject().put("dryRun", dryRun).put("bundles", reset) //$NON-NLS-1$ //$NON-NLS-2$
				.put("total", total).put("truncated", truncated) //$NON-NLS-1$ //$NON-NLS-2$
				.put("inMemoryKnown", loaded != null); //$NON-NLS-1$
		if (loaded == null) {
			result.put("note", //$NON-NLS-1$
					"The platform's list of loaded dialog settings could not be read, so only bundles with a settings file were reset; settings that exist only in memory were left alone."); //$NON-NLS-1$
		}
		if (!unknown.isEmpty()) {
			result.put("unknownBundles", capped(unknown, maxResults)); //$NON-NLS-1$
		}
		if (!nothingToReset.isEmpty()) {
			result.put("nothingToReset", capped(nothingToReset, maxResults)); //$NON-NLS-1$
		}
		if (!failures.isEmpty()) {
			result.put("failures", capped(failures, maxResults)).put("failuresTotal", failures.size()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonArray openDialogs = openDialogs();
		if (openDialogs.size() > 0) {
			result.put("openDialogs", openDialogs).put("openDialogsNote", //$NON-NLS-1$ //$NON-NLS-2$
					"These dialogs may have read their settings before the reset and save them when they close, which writes the old answers back. Close them and reset again."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private static JsonArray capped(List<?> all, int max) {
		JsonArray part = new JsonArray();
		all.stream().limit(max).forEach(part::add);
		return part;
	}

	/**
	 * Titles of the visible modal dialogs, empty without a workbench or when the
	 * UI thread does not answer in time.
	 */
	private static JsonArray openDialogs() {
		UiThread.TimedOutcome outcome = UiThread.timed(5, () -> {
			JsonArray titles = new JsonArray();
			for (Shell shell : Workbenches.display().getShells()) {
				if (!shell.isDisposed() && shell.isVisible() && Shells.isModal(shell)) {
					titles.add(shell.getText());
				}
			}
			return new JsonObject().put("titles", titles); //$NON-NLS-1$
		});
		return outcome.value() != null && outcome.value().remove("titles") instanceof JsonArray titles ? titles //$NON-NLS-1$
				: new JsonArray();
	}

	private static Path pluginStateArea() {
		var location = Platform.getInstanceLocation();
		if (location == null || !location.isSet() || location.getURL() == null) {
			return null;
		}
		Path workspace = FileLocations.pathOf(location.getURL());
		return workspace == null ? null : workspace.resolve(".metadata").resolve(".plugins"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The bundles whose dialog settings provider exists, read from the platform's
	 * provider map, or {@code null} when the map cannot be read.
	 */
	private static Set<Bundle> loadedBundles() {
		try {
			Bundle owner = Platform.getBundle(PROVIDER_BUNDLE);
			if (owner == null) {
				return null;
			}
			Field field = owner.loadClass(PROVIDER_SERVICE).getDeclaredField("fTrackedBundles"); //$NON-NLS-1$
			field.setAccessible(true);
			Map<?, ?> tracked = (Map<?, ?>) field.get(null);
			synchronized (tracked) {
				Set<Bundle> bundles = new HashSet<>();
				for (Object key : tracked.keySet()) {
					if (key instanceof Bundle bundle) {
						bundles.add(bundle);
					}
				}
				return bundles;
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}
}
