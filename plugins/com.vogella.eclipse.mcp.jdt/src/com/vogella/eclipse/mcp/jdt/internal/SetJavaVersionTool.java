package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.JreUsability;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Sets the Java version a project or the workspace compiles for, and the JDK
 * behind it.
 */
public final class SetJavaVersionTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_set_java_version"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Sets the Java version to compile for, and optionally the JDK behind it. MODIFIES THE IDE CONFIGURATION, and runs as a dry run unless dryRun is set to false. Two different things are set here and mixing them up is the usual mistake. compliance is what the code is compiled AS, source, target and compliance together through JavaCore, which is what a migration to a newer release changes. defaultVm is which installed JDK the workspace uses where a project's JRE container names no execution environment, and that one is shared: every such project follows it at once. The JDK is checked before it is chosen, because a JDK can be present and still unable to compile: one whose lib/ct.sym is missing fails every project bound to it with a message about ct.sym that names neither a project nor a version. NOTE THAT A TARGET PLATFORM OVERWRITES defaultVm: activating a target definition that names a JRE replaces it, so setting this and then activating such a target puts the target's choice back."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["version"],
				  "properties": {
				    "version":   {"type":"string","description":"Java version, such as 25, 21 or 1.8."},
				    "project":   {"type":"string","description":"Project whose compliance to set. Omit to set the workspace default compliance, which every project without its own settings follows."},
				    "compliance":{"type":"boolean","default":true,"description":"Set source, target and compliance together, the way the compiler preference page does. This is the migration setting."},
				    "release":   {"type":"boolean","description":"Set the compiler's 'release' option, which makes the compile refuse API newer than the target version instead of only warning. Left alone when omitted."},
				    "defaultVm": {"type":"boolean","default":false,"description":"Also point the WORKSPACE default VM at an installed JDK of this version. Shared by every project whose JRE container names no execution environment, so this changes them all."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report what would change, including which JDK would be chosen and whether it can compile, and change nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String version = args.getString("version"); //$NON-NLS-1$
		if (version == null) {
			return McpToolResult.error("The argument 'version' is required, for instance 25 or 1.8."); //$NON-NLS-1$
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		boolean compliance = args.getBoolean("compliance", true); //$NON-NLS-1$
		boolean defaultVm = args.getBoolean("defaultVm", false); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$

		IJavaProject javaProject = null;
		if (projectName != null) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
			}
			javaProject = JavaCore.create(project);
			if (javaProject == null || !javaProject.exists()) {
				return McpToolResult.error("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
			}
		}

		if (compliance && !knownVersion(version)) {
			return McpToolResult.error("'%s' is not a Java version JDT knows, for instance 25, 21 or 1.8.".formatted(version)); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("version", version) //$NON-NLS-1$
				.put("scope", javaProject == null ? "workspace" : projectName) //$NON-NLS-1$ //$NON-NLS-2$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$
		if (compliance) {
			result.put("compliance", compliance(javaProject, version, args, dryRun)); //$NON-NLS-1$
		}
		if (defaultVm) {
			result.put("defaultVm", defaultVm(version, dryRun, monitor)); //$NON-NLS-1$
		}
		result.put("note", note(dryRun, defaultVm)); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static boolean knownVersion(String version) {
		Map<String, String> probe = new LinkedHashMap<>();
		JavaCore.setComplianceOptions(version, probe);
		return !probe.isEmpty();
	}

	private static String note(boolean dryRun, boolean defaultVm) {
		StringBuilder note = new StringBuilder();
		if (dryRun) {
			note.append("Nothing was changed. Pass dryRun false to apply it. "); //$NON-NLS-1$
		} else {
			note.append(
					"Changing compliance does not rebuild by itself: run eclipse_build with kind full, since the markers of an unbuilt project describe the old settings. "); //$NON-NLS-1$
		}
		if (defaultVm) {
			note.append(
					"The workspace default VM is shared, and activating a target platform that names a JRE overwrites it again."); //$NON-NLS-1$
		}
		return note.toString();
	}

	/**
	 * Sets source, target and compliance together.
	 * <p>
	 * Through {@code JavaCore.setComplianceOptions} rather than by writing the
	 * three keys, because they constrain each other and a combination that JDT
	 * would not have produced is how a project ends up compiling for one version
	 * and generating class files for another.
	 */
	private static JsonObject compliance(IJavaProject javaProject, String version, ToolArguments args, boolean dryRun) {
		Map<String, String> after = read(javaProject, List.of(KEYS));
		JavaCore.setComplianceOptions(version, after);
		if (args.has("release")) { //$NON-NLS-1$
			after.put(JavaCore.COMPILER_RELEASE, args.getBoolean("release", false) ? JavaCore.ENABLED //$NON-NLS-1$
					: JavaCore.DISABLED);
		}
		// setComplianceOptions also sets keys such as assertIdentifier, which the comparison has to see too
		Map<String, String> before = read(javaProject, after.keySet());
		JsonObject json = new JsonObject().put("previous", of(before)).put("applied", of(after)); //$NON-NLS-1$ //$NON-NLS-2$
		if (before.equals(after)) {
			return json.put("changed", Boolean.FALSE) //$NON-NLS-1$
					.put("note", "Already set to this, so nothing would change."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!dryRun) {
			if (javaProject == null) {
				Hashtable<String, String> options = JavaCore.getOptions();
				options.putAll(after);
				JavaCore.setOptions(options);
			} else {
				// only what differs, so values inherited from the workspace stay inherited
				after.forEach((key, value) -> {
					if (!value.equals(before.get(key))) {
						javaProject.setOption(key, value);
					}
				});
			}
		}
		return json.put("changed", Boolean.TRUE); //$NON-NLS-1$
	}

	private static final String[] KEYS = { JavaCore.COMPILER_SOURCE, JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,
			JavaCore.COMPILER_COMPLIANCE, JavaCore.COMPILER_RELEASE };

	private static Map<String, String> read(IJavaProject javaProject, Collection<String> keys) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : keys) {
			String value = javaProject == null ? JavaCore.getOption(key) : javaProject.getOption(key, true);
			if (value != null) {
				values.put(key, value);
			}
		}
		return values;
	}

	/** Points the workspace default VM at an installed JDK of this version. */
	private static JsonObject defaultVm(String version, boolean dryRun, IProgressMonitor monitor) {
		IVMInstall current = JavaRuntime.getDefaultVMInstall();
		JsonObject json = new JsonObject().put("previous", describe(current)); //$NON-NLS-1$
		JsonArray candidates = new JsonArray();
		IVMInstall best = null;
		for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
			for (IVMInstall vm : type.getVMInstalls()) {
				if (!matches(vm, version)) {
					continue;
				}
				candidates.add(describe(vm));
				// a JDK that cannot compile is not a candidate, however well its version
				// matches: that is the trap this whole check exists for
				if (best == null && JreUsability.reason(vm.getInstallLocation()) == null) {
					best = vm;
				}
			}
		}
		json.put("candidates", candidates); //$NON-NLS-1$
		if (best == null) {
			return json.put("changed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", candidates.size() == 0 //$NON-NLS-1$
							? "No installed VM reports version %s. Add one in Preferences > Java > Installed JREs.".formatted(version) //$NON-NLS-1$
							: "Every installed VM of version %s failed the usability check, so none was chosen; the candidates say why.".formatted(version)); //$NON-NLS-1$
		}
		json.put("chosen", describe(best)); //$NON-NLS-1$
		if (current == best) {
			return json.put("changed", Boolean.FALSE).put("reason", "It is already the default."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		if (!dryRun) {
			try {
				JavaRuntime.setDefaultVMInstall(best, monitor);
			} catch (CoreException e) {
				return json.put("changed", Boolean.FALSE).put("error", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return json.put("changed", Boolean.TRUE); //$NON-NLS-1$
	}

	private static boolean matches(IVMInstall vm, String version) {
		String installed = vm instanceof IVMInstall2 two ? two.getJavaVersion() : null;
		if (installed == null) {
			return false;
		}
		String wanted = version.startsWith("1.") ? version : version + "."; //$NON-NLS-1$ //$NON-NLS-2$
		return installed.equals(version) || installed.startsWith(wanted);
	}

	private static JsonObject describe(IVMInstall vm) {
		if (vm == null) {
			return null;
		}
		JsonObject json = new JsonObject().put("name", vm.getName()) //$NON-NLS-1$
				.put("javaVersion", vm instanceof IVMInstall2 two ? two.getJavaVersion() : null) //$NON-NLS-1$
				.put("location", vm.getInstallLocation() == null ? null //$NON-NLS-1$
						: vm.getInstallLocation().getAbsolutePath());
		String unusable = JreUsability.reason(vm.getInstallLocation());
		return unusable == null ? json.put("usable", Boolean.TRUE) //$NON-NLS-1$
				: json.put("usable", Boolean.FALSE).put("warning", unusable); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject of(Map<String, String> values) {
		JsonObject json = new JsonObject();
		values.forEach(json::put);
		return json;
	}
}
