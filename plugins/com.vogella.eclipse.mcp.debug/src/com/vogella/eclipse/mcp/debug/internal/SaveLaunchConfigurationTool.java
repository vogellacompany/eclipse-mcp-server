package com.vogella.eclipse.mcp.debug.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Creates or updates a saved launch configuration, so eclipse_debug_launch can start it by name.
 */
public final class SaveLaunchConfigurationTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_save_launch_configuration"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "CREATES OR UPDATES A SAVED LAUNCH CONFIGURATION, visible to the person in the Run and Debug dialogs. DRY RUN BY DEFAULT: with dryRun true (the default) nothing is written and the answer shows the attributes that would be set. Only the attributes you pass are set; on an existing configuration of the same name and type the others are kept, and 'environment' is merged over its variables. It refuses to replace a configuration of the same name that has another type unless allowTypeChange is true. Defaults to a local Java Application. Start the result with eclipse_debug_launch by name."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "name":              {"type":"string","description":"Name of the configuration."},
				    "type":              {"type":"string","default":"org.eclipse.jdt.launching.localJavaApplication","description":"Launch configuration type id, as eclipse_list_launch_configurations lists them."},
				    "project":           {"type":"string","description":"Project the program runs in; with storage 'project' also where the .launch file goes."},
				    "mainType":          {"type":"string","description":"Fully qualified main class."},
				    "vmArguments":       {"type":"string"},
				    "programArguments":  {"type":"string"},
				    "environment":       {"type":"object","additionalProperties":{"type":"string"},"description":"Extra environment variables, merged over the IDE's own and over the configuration's existing ones."},
				    "classpathProvider": {"type":"string","description":"Classpath provider id, for example org.eclipse.buildship.core.classpathprovider."},
				    "storage":           {"type":"string","enum":["workspace","project"],"default":"workspace","description":"'workspace' keeps it in the workspace metadata, 'project' writes a .launch file into the project, which needs 'project'."},
				    "dryRun":            {"type":"boolean","default":true,"description":"Show the attributes without writing anything."},
				    "allowTypeChange":   {"type":"boolean","default":false,"description":"Replace an existing configuration of the same name that has another type."}
				  },
				  "required": ["name"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String name = args.getString("name"); //$NON-NLS-1$
		if (name == null || name.isBlank()) {
			throw new McpToolException("'name' is required."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean inProject = "project".equals(args.getString("storage", "workspace")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		String typeId = args.getString("type", IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = manager.getLaunchConfigurationType(typeId);
		if (type == null) {
			throw new McpToolException("No launch configuration type '%s' is installed.".formatted(typeId)); //$NON-NLS-1$
		}
		IProject project = null;
		if (projectName != null) {
			project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.isAccessible()) {
				throw new McpToolException("No open project named '%s'.".formatted(projectName)); //$NON-NLS-1$
			}
		}
		if (inProject && project == null) {
			throw new McpToolException("storage 'project' needs 'project'."); //$NON-NLS-1$
		}
		try {
			ILaunchConfiguration existing = find(manager, name, inProject ? project : null, inProject);
			if (existing != null && !args.getBoolean("allowTypeChange", false) //$NON-NLS-1$
					&& !existing.getType().getIdentifier().equals(typeId)) {
				throw new McpToolException(("A configuration named '%s' exists with type %s. Pass allowTypeChange true to replace it.") //$NON-NLS-1$
						.formatted(name, existing.getType().getIdentifier()));
			}
			boolean update = existing != null && existing.getType().getIdentifier().equals(typeId);
			ILaunchConfigurationWorkingCopy copy;
			if (update) {
				copy = existing.getWorkingCopy();
			} else {
				copy = type.newInstance(inProject ? project : null, name);
			}
			Map<String, Object> set = new LinkedHashMap<>();
			put(copy, set, IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
			put(copy, set, IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, args.getString("mainType")); //$NON-NLS-1$
			put(copy, set, IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, args.getString("vmArguments")); //$NON-NLS-1$
			put(copy, set, IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, args.getString("programArguments")); //$NON-NLS-1$
			put(copy, set, IJavaLaunchConfigurationConstants.ATTR_CLASSPATH_PROVIDER, args.getString("classpathProvider")); //$NON-NLS-1$
			if (arguments.get("environment") instanceof Map<?, ?> extra && !extra.isEmpty()) { //$NON-NLS-1$
				Map<String, String> merged = new LinkedHashMap<>(
						copy.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.<String, String>of()));
				extra.forEach((key, value) -> merged.put(String.valueOf(key), String.valueOf(value)));
				copy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, merged);
				copy.setAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true);
				set.put(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, merged);
			}
			JsonObject attributes = new JsonObject();
			set.forEach((key, value) -> attributes.put(key, value instanceof Map<?, ?> map ? map.toString() : value));
			JsonObject json = new JsonObject().put("name", name).put("type", typeId) //$NON-NLS-1$ //$NON-NLS-2$
					.put("storage", inProject ? "project" : "workspace") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("action", update ? "update" : existing != null ? "replace" : "create") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					.put("attributes", attributes) //$NON-NLS-1$
					.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$
			String elsewhere = elsewhere(manager, name, existing);
			if (elsewhere != null) {
				json.put("warning", elsewhere); //$NON-NLS-1$
			}
			if (dryRun) {
				return McpToolResult.of(json.put("note", "Nothing was written. Pass dryRun false to save.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (existing != null && !update) {
				existing.delete();
			}
			ILaunchConfiguration saved = copy.doSave();
			json.put("saved", Boolean.TRUE).put("file", //$NON-NLS-1$ //$NON-NLS-2$
					saved.getFile() == null ? null : saved.getFile().getFullPath().toString());
			return McpToolResult.of(json.toString());
		} catch (CoreException e) {
			throw new McpToolException("Could not save the launch configuration: %s".formatted(e.getMessage()), e); //$NON-NLS-1$
		}
	}

	private static void put(ILaunchConfigurationWorkingCopy copy, Map<String, Object> set, String key, String value) {
		if (value != null) {
			copy.setAttribute(key, value);
			set.put(key, value);
		}
	}

	private static String elsewhere(ILaunchManager manager, String name, ILaunchConfiguration target)
			throws CoreException {
		for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
			if (candidate.getName().equals(name) && !candidate.equals(target)) {
				return "Another launch configuration named '%s' is stored elsewhere, so launching by name needs 'project' to tell them apart." //$NON-NLS-1$
						.formatted(name);
			}
		}
		return null;
	}

	/** The configuration of this name in the target storage, so a same-named one elsewhere is left alone. */
	private static ILaunchConfiguration find(ILaunchManager manager, String name, IProject project, boolean inProject)
			throws CoreException {
		List<ILaunchConfiguration> matches = new ArrayList<>();
		for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
			if (!candidate.getName().equals(name)) {
				continue;
			}
			boolean same = inProject ? candidate.getFile() != null && candidate.getFile().getProject().equals(project)
					: candidate.isLocal();
			if (same) {
				matches.add(candidate);
			}
		}
		return matches.isEmpty() ? null : matches.get(0);
	}
}
