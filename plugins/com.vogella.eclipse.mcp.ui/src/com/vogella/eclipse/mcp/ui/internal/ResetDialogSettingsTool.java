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
		return "RESETS the answers wizards and dialogs remembered (IDialogSettings), such as the last choices in PDE's New Plug-in Project wizard, so the next wizard opens with its defaults without restarting the IDE. Defaults to a dry run that lists what would be reset. For each bundle it DELETES <workspace>/.metadata/.plugins/<bundle>/dialog_settings.xml and reloads the in-memory settings, which then come from the product or bundle defaults or start empty. Without bundles it acts on every installed bundle that has settings in memory or on disk; onDisk and inMemory say which of the two each bundle had, and a named bundle with neither is listed under nothingToReset. A wizard or dialog already open keeps what it read and writes it to the discarded copy."; //$NON-NLS-1$
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
		Path pluginsArea = pluginsArea();
		if (pluginsArea == null) {
			return McpToolResult.error("The IDE has no workspace, so there are no dialog settings to reset."); //$NON-NLS-1$
		}
		Set<Bundle> loaded = loadedBundles();

		Map<String, Bundle> candidates = new TreeMap<>();
		JsonArray unknown = new JsonArray();
		if (arguments != null && arguments.get("bundles") instanceof List<?> requested) { //$NON-NLS-1$
			for (Object name : requested) {
				Bundle bundle = Platform.getBundle(String.valueOf(name));
				if (bundle == null) {
					unknown.add(String.valueOf(name));
				} else {
					candidates.put(bundle.getSymbolicName(), bundle);
				}
			}
		} else {
			if (loaded != null) {
				loaded.forEach(b -> candidates.put(b.getSymbolicName(), b));
			}
			try (Stream<Path> dirs = Files.list(pluginsArea)) {
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
		JsonArray nothingToReset = new JsonArray();
		List<String> failures = new ArrayList<>();
		int total = 0;
		for (Bundle bundle : candidates.values()) {
			Path file = pluginsArea.resolve(bundle.getSymbolicName()).resolve(FILE_NAME);
			boolean onDisk = Files.isRegularFile(file);
			// without the provider map every candidate is reloaded, since nothing tells which are loaded
			boolean inMemory = loaded == null || loaded.contains(bundle);
			if (!onDisk && !inMemory) {
				nothingToReset.add(bundle.getSymbolicName());
				continue;
			}
			JsonObject entry = new JsonObject().put("bundle", bundle.getSymbolicName()).put("onDisk", onDisk); //$NON-NLS-1$ //$NON-NLS-2$
			if (loaded != null) {
				entry.put("inMemory", inMemory); //$NON-NLS-1$
			}
			if (!dryRun) {
				try {
					// the file goes first, or the reload reads the old answers back
					Files.deleteIfExists(file);
					if (inMemory) {
						PlatformUI.getDialogSettingsProvider(bundle).loadDialogSettings();
					}
				} catch (IOException | RuntimeException e) {
					failures.add(bundle.getSymbolicName() + ": " + e); //$NON-NLS-1$
					continue;
				}
			}
			total++;
			if (total <= maxResults) {
				reset.add(entry);
			}
		}

		JsonObject result = new JsonObject().put("dryRun", dryRun).put("bundles", reset) //$NON-NLS-1$ //$NON-NLS-2$
				.put("total", total).put("truncated", total > maxResults) //$NON-NLS-1$ //$NON-NLS-2$
				.put("inMemoryKnown", loaded != null); //$NON-NLS-1$
		if (loaded == null) {
			result.put("note", //$NON-NLS-1$
					"The platform's list of loaded dialog settings could not be read, so bundles whose settings exist only in memory were not found. Name them under bundles to reset them."); //$NON-NLS-1$
		}
		if (unknown.size() > 0) {
			result.put("unknownBundles", unknown); //$NON-NLS-1$
		}
		if (nothingToReset.size() > 0) {
			result.put("nothingToReset", nothingToReset); //$NON-NLS-1$
		}
		if (!failures.isEmpty()) {
			JsonArray failed = new JsonArray();
			failures.forEach(failed::add);
			result.put("failures", failed); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private static Path pluginsArea() {
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
