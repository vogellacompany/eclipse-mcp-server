package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.JreUsability;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the build path of a project as JDT resolved it, containers expanded.
 */
public final class GetClasspathTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_classpath"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the build path of a Java project as JDT resolved it: containers expanded to the jars behind them, the JRE actually bound to the JRE container, source and output folders, access rules, and which raw entry each resolved entry came from. Reading .classpath does not answer this. A JavaSE-1.8 container says nothing about which JDK the IDE bound to it, and that binding is what decides whether a --release compile works."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["project"],
				  "properties": {
				    "project":    {"type":"string","description":"Project name."},
				    "resolved":   {"type":"boolean","default":true,"description":"Expand containers and variables. With false, only the raw entries of .classpath are reported."},
				    "maxResults": {"type":"integer","default":500,"minimum":1,"maximum":5000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("The argument 'project' is required."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			return McpToolResult.error("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
		}
		boolean resolved = args.getBoolean("resolved", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 500, 1, 5000); //$NON-NLS-1$

		try {
			JsonObject result = new JsonObject().put("project", projectName) //$NON-NLS-1$
					.put("resolved", resolved) //$NON-NLS-1$
					.put("outputLocation", javaProject.getOutputLocation().toString()); //$NON-NLS-1$

			IClasspathEntry[] rawEntries = javaProject.getRawClasspath();
			JsonArray raw = new JsonArray();
			for (IClasspathEntry entry : rawEntries) {
				if (raw.size() >= maxResults) {
					break;
				}
				raw.add(describeRaw(javaProject, entry));
			}
			result.put("rawTotal", rawEntries.length) //$NON-NLS-1$
					.put("rawTruncated", rawEntries.length > raw.size()) //$NON-NLS-1$
					.put("rawEntries", raw); //$NON-NLS-1$

			if (resolved) {
				IClasspathEntry[] entries = javaProject.getResolvedClasspath(true);
				JsonArray reported = new JsonArray();
				for (IClasspathEntry entry : entries) {
					if (reported.size() >= maxResults) {
						break;
					}
					reported.add(describeResolved(entry));
				}
				result.put("total", entries.length) //$NON-NLS-1$
						.put("truncated", entries.length > reported.size()) //$NON-NLS-1$
						.put("resolvedEntries", reported); //$NON-NLS-1$
			}
			return McpToolResult.of(result.toString());
		} catch (CoreException e) {
			throw new McpToolException("Could not read the build path of " + projectName, e); //$NON-NLS-1$
		}
	}

	private static JsonObject describeRaw(IJavaProject javaProject, IClasspathEntry entry) {
		JsonObject json = new JsonObject().put("kind", kindOf(entry)) //$NON-NLS-1$
				.put("path", entry.getPath().toString()) //$NON-NLS-1$
				.put("exported", entry.isExported()); //$NON-NLS-1$
		if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
			addContainer(javaProject, entry, json);
		}
		if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && entry.getOutputLocation() != null) {
			json.put("outputLocation", entry.getOutputLocation().toString()); //$NON-NLS-1$
		}
		addRules(entry, json);
		return json;
	}

	/**
	 * A container path names an intent, not a location; the description and the bound JRE decide how the
	 * project compiles and are not in {@code .classpath}.
	 */
	private static void addContainer(IJavaProject javaProject, IClasspathEntry entry, JsonObject json) {
		try {
			IClasspathContainer container = JavaCore.getClasspathContainer(entry.getPath(), javaProject);
			json.put("containerDescription", container == null ? null : container.getDescription()); //$NON-NLS-1$
			json.put("containerEntries", container == null ? null //$NON-NLS-1$
					: Integer.valueOf(container.getClasspathEntries().length));
		} catch (CoreException e) {
			json.put("containerDescription", "unresolved: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!JavaRuntime.JRE_CONTAINER.equals(entry.getPath().segment(0))) {
			return;
		}
		try {
			IVMInstall vm = JavaRuntime.getVMInstall(javaProject);
			if (vm != null) {
				JsonObject bound = new JsonObject().put("name", vm.getName()) //$NON-NLS-1$
						.put("location", vm.getInstallLocation() == null ? null //$NON-NLS-1$
								: vm.getInstallLocation().getAbsolutePath())
						.put("type", vm.getVMInstallType() == null ? null : vm.getVMInstallType().getName()); //$NON-NLS-1$
				// no execution environment segment means the shared workspace default VM
				if (entry.getPath().segmentCount() == 1) {
					bound.put("fromWorkspaceDefault", Boolean.TRUE); //$NON-NLS-1$
				}
				String unusable = JreUsability.reason(vm.getInstallLocation());
				if (unusable != null) {
					bound.put("usable", Boolean.FALSE).put("warning", unusable); //$NON-NLS-1$ //$NON-NLS-2$
				}
				json.put("boundJre", bound); //$NON-NLS-1$
			}
		} catch (CoreException e) {
			json.put("boundJre", null); //$NON-NLS-1$
		}
	}

	private static JsonObject describeResolved(IClasspathEntry entry) {
		JsonObject json = new JsonObject().put("kind", kindOf(entry)) //$NON-NLS-1$
				.put("path", entry.getPath().toString()) //$NON-NLS-1$
				.put("exported", entry.isExported()); //$NON-NLS-1$
		IPath source = entry.getSourceAttachmentPath();
		json.put("sourceAttachment", source == null ? null : source.toString()); //$NON-NLS-1$
		IClasspathAttribute[] attributes = entry.getExtraAttributes();
		if (attributes.length > 0) {
			JsonObject extra = new JsonObject();
			for (IClasspathAttribute attribute : attributes) {
				extra.put(attribute.getName(), attribute.getValue());
			}
			json.put("attributes", extra); //$NON-NLS-1$
		}
		addRules(entry, json);
		return json;
	}

	private static void addRules(IClasspathEntry entry, JsonObject json) {
		IAccessRule[] rules = entry.getAccessRules();
		if (rules.length == 0) {
			return;
		}
		JsonArray array = new JsonArray();
		for (IAccessRule rule : rules) {
			array.add(new JsonObject().put("pattern", rule.getPattern().toString()) //$NON-NLS-1$
					.put("kind", ruleKind(rule.getKind()))); //$NON-NLS-1$
		}
		json.put("accessRules", array); //$NON-NLS-1$
	}

	private static String ruleKind(int kind) {
		return switch (kind) {
		case IAccessRule.K_ACCESSIBLE -> "accessible"; //$NON-NLS-1$
		case IAccessRule.K_DISCOURAGED -> "discouraged"; //$NON-NLS-1$
		case IAccessRule.K_NON_ACCESSIBLE -> "forbidden"; //$NON-NLS-1$
		default -> String.valueOf(kind);
		};
	}

	private static String kindOf(IClasspathEntry entry) {
		return switch (entry.getEntryKind()) {
		case IClasspathEntry.CPE_SOURCE -> "source"; //$NON-NLS-1$
		case IClasspathEntry.CPE_LIBRARY -> "library"; //$NON-NLS-1$
		case IClasspathEntry.CPE_PROJECT -> "project"; //$NON-NLS-1$
		case IClasspathEntry.CPE_CONTAINER -> "container"; //$NON-NLS-1$
		case IClasspathEntry.CPE_VARIABLE -> "variable"; //$NON-NLS-1$
		default -> String.valueOf(entry.getEntryKind());
		};
	}
}
