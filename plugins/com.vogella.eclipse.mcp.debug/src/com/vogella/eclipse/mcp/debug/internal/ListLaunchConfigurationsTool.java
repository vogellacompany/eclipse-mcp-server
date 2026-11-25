package com.vogella.eclipse.mcp.debug.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.LaunchAttributes;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the launch configurations this IDE knows, which is what makes
 * eclipse_debug_launch usable without guessing at names.
 */
public final class ListLaunchConfigurationsTool implements IMcpTool {

	/** Attributes worth showing without dumping a configuration's whole map. */
	private static final String[] INTERESTING = { "org.eclipse.jdt.launching.PROJECT_ATTR", //$NON-NLS-1$
			"org.eclipse.jdt.launching.MAIN_TYPE", //$NON-NLS-1$
			"org.eclipse.jdt.launching.VM_ARGUMENTS", //$NON-NLS-1$
			"org.eclipse.jdt.launching.PROGRAM_ARGUMENTS", //$NON-NLS-1$
			"application", "product", "useProduct", "location", "use_default" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	@Override
	public String getName() {
		return "eclipse_list_launch_configurations"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the launch configurations of this IDE with their type, the modes they support and whether they are stored in the workspace or in a project. Changes nothing. eclipse_debug_launch takes a configuration by NAME, and without this the names can only be found by reading .metadata/.plugins/org.eclipse.debug.core/.launches from the file system, which is where this server should not send anybody. It also lists the launch configuration TYPES, which is what says whether this IDE can start an Eclipse Application or a plug-in test at all: a runtime workbench has no main class, so 'project' plus 'mainType' cannot express it and an existing configuration is the only way in. A .launch file whose type is not installed is listed with launchable false and missingType, since the launch manager leaves it out. Configurations this server created for its own launches are marked, since they are transient and belong to nobody's saved launches."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "typeFilter":     {"type":"string","description":"Only configurations whose type id or name contains this text, e.g. 'RuntimeWorkbench' or 'junit'."},
				    "nameFilter":     {"type":"string","description":"Only configurations whose name contains this text."},
				    "includeTypes":   {"type":"boolean","default":true,"description":"Also list the launch configuration types this IDE offers, which is what says what can be launched at all."},
				    "includeAttributes":{"type":"boolean","default":false,"description":"Include the common attributes of each configuration, such as project, main type and VM arguments."},
				    "maxResults":     {"type":"integer","default":100,"minimum":1,"maximum":1000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "configurations": {"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"type":{"type":"string"},"typeName":{"type":"string"},"launchable":{"type":"boolean"},"modes":{"type":"array","items":{"type":"string"}},"storage":{"type":"string"},"file":{"type":["string","null"]},"startedByMcp":{"type":"boolean"},"note":{"type":"string"},"attributes":{"type":"object"},"missingType":{"type":"string"},"path":{"type":["string","null"]},"nearestBundle":{"type":"string"},"nearestBundleState":{"type":"string"}}}},
				    "total":          {"type":"integer"},
				    "truncated":      {"type":"boolean"},
				    "types":          {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"name":{"type":"string"},"modes":{"type":"array","items":{"type":"string"}}}}},
				    "note":           {"type":"string"}
				  },
				  "required": ["configurations","total","truncated"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String typeFilter = lower(args.getString("typeFilter")); //$NON-NLS-1$
		String nameFilter = lower(args.getString("nameFilter")); //$NON-NLS-1$
		boolean withAttributes = args.getBoolean("includeAttributes", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();

		JsonArray configurations = new JsonArray();
		int total = 0;
		try {
			for (ILaunchConfiguration configuration : manager.getLaunchConfigurations()) {
				ILaunchConfigurationType type = configuration.getType();
				if (!matches(nameFilter, configuration.getName())
						|| !(matches(typeFilter, type.getIdentifier()) || matches(typeFilter, type.getName()))) {
					continue;
				}
				total++;
				if (configurations.size() >= maxResults) {
					continue;
				}
				configurations.add(describe(configuration, type, withAttributes));
			}
			for (MissingType missing : MissingType.scan(manager)) {
				if (!matches(nameFilter, missing.name()) || !matches(typeFilter, missing.typeId())) {
					continue;
				}
				total++;
				if (configurations.size() < maxResults) {
					configurations.add(missing.json());
				}
			}
		} catch (CoreException e) {
			return McpToolResult.error("Could not read the launch configurations: " + e.getMessage()); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("configurations", configurations) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total > configurations.size())); //$NON-NLS-1$
		if (args.getBoolean("includeTypes", true)) { //$NON-NLS-1$
			result.put("types", types(manager, typeFilter)); //$NON-NLS-1$
		}
		if (total == 0) {
			result.put("note", //$NON-NLS-1$
					"No configuration matched. A type that exists with no configuration of it means the kind of launch is possible but nobody has set one up; an Eclipse Application has no main class, so one has to exist before eclipse_debug_launch can start it."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private static JsonObject describe(ILaunchConfiguration configuration, ILaunchConfigurationType type,
			boolean withAttributes) throws CoreException {
		JsonObject json = new JsonObject().put("name", configuration.getName()) //$NON-NLS-1$
				.put("type", type.getIdentifier()) //$NON-NLS-1$
				.put("typeName", type.getName()) //$NON-NLS-1$
				.put("launchable", Boolean.TRUE) //$NON-NLS-1$
				.put("modes", modes(type)) //$NON-NLS-1$
				.put("storage", configuration.isLocal() ? "workspace metadata" : "project file") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("file", configuration.getFile() == null ? null //$NON-NLS-1$
						: configuration.getFile().getFullPath().toString());
		if (configuration.getAttribute(LaunchAttributes.STARTED_BY_MCP, false)) {
			json.put("startedByMcp", Boolean.TRUE).put("note", //$NON-NLS-1$ //$NON-NLS-2$
					"Created by this server for one of its own launches, not by the person at the IDE."); //$NON-NLS-1$
		}
		if (withAttributes) {
			JsonObject attributes = new JsonObject();
			for (String key : INTERESTING) {
				String value = attribute(configuration, key);
				if (value != null) {
					attributes.put(key, value);
				}
			}
			json.put("attributes", attributes); //$NON-NLS-1$
		}
		return json;
	}

	/**
	 * One attribute as text, whatever it is stored as.
	 * <p>
	 * A launch attribute is typed, and asking for the wrong type throws rather than
	 * answering null: useProduct on a PDE runtime workbench is a Boolean, and
	 * reading every attribute as a String failed the whole call over it.
	 */
	private static String attribute(ILaunchConfiguration configuration, String key) {
		try {
			return configuration.getAttribute(key, (String) null);
		} catch (CoreException e) {
			// stored under another type; fall through and try the ones that occur
		}
		try {
			return String.valueOf(configuration.getAttribute(key, false));
		} catch (CoreException e) {
			// not a boolean either
		}
		try {
			return String.valueOf(configuration.getAttribute(key, 0));
		} catch (CoreException e) {
			return null;
		}
	}

	private static JsonArray modes(ILaunchConfigurationType type) {
		JsonArray modes = new JsonArray();
		for (String mode : List.of(ILaunchManager.RUN_MODE, ILaunchManager.DEBUG_MODE,
				ILaunchManager.PROFILE_MODE)) {
			if (type.supportsMode(mode)) {
				modes.add(mode);
			}
		}
		return modes;
	}

	/** What this IDE can launch at all, which a missing configuration does not answer. */
	private static JsonArray types(ILaunchManager manager, String typeFilter) {
		JsonArray types = new JsonArray();
		for (ILaunchConfigurationType type : manager.getLaunchConfigurationTypes()) {
			if (!type.isPublic() || !(matches(typeFilter, type.getIdentifier()) || matches(typeFilter, type.getName()))) {
				continue;
			}
			types.add(new JsonObject().put("id", type.getIdentifier()) //$NON-NLS-1$
					.put("name", type.getName()) //$NON-NLS-1$
					.put("modes", modes(type))); //$NON-NLS-1$
		}
		return types;
	}

	private static boolean matches(String filter, String value) {
		return filter == null || (value != null && value.toLowerCase(Locale.ROOT).contains(filter));
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
