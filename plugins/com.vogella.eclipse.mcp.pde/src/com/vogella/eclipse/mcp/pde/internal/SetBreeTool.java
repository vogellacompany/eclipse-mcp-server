package com.vogella.eclipse.mcp.pde.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.pde.core.project.IBundleProjectDescription;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Sets the execution environment of plug-in projects, together with the JDT
 * compiler settings that have to agree with it.
 */
public final class SetBreeTool implements IMcpTool {

	private static final String PLUGIN_NATURE = "org.eclipse.pde.PluginNature"; //$NON-NLS-1$

	/** The compiler options compared to decide whether a project is up to date. */
	private static final List<String> COMPLIANCE_KEYS = List.of(JavaCore.COMPILER_COMPLIANCE,
			JavaCore.COMPILER_SOURCE, JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM);

	@Override
	public String getName() {
		return "eclipse_set_bree"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Sets the Bundle-RequiredExecutionEnvironment of plug-in projects and the JDT compiler compliance that has to match it, in one operation. MODIFIES THE WORKSPACE: it rewrites META-INF/MANIFEST.MF, points the JRE container in .classpath at the environment (adding one if there is none), and writes every compiler option the environment dictates, including release, as the PDE manifest editor does. A project whose header already matches but whose JRE container or compiler settings do not is repaired too. Runs as a dry run unless dryRun is set to false. Setting the BREE without the compiler settings leaves a project whose manifest and compiler disagree, which is why the two are done together."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "bree":             {"type":"string","description":"Execution environment id, for example JavaSE-21. Must be one the IDE knows."},
				    "projects":         {"type":"array","items":{"type":"string"},"description":"Plug-in project names to act on."},
				    "namePattern":      {"type":"string","description":"Glob over project names, '*' and '?' allowed, case insensitive."},
				    "currentBree":      {"type":"string","description":"Only act on projects currently declaring this environment. Use it to move everything off one version."},
				    "updateCompliance": {"type":"boolean","default":true,"description":"Also set the compiler options the environment dictates: compliance, source, target, release and the related problem severities."},
				    "dryRun":           {"type":"boolean","default":true,"description":"Report what would change without writing anything."},
				    "maxResults":       {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "required": ["bree"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String bree = args.getString("bree"); //$NON-NLS-1$
		if (bree == null) {
			return McpToolResult.error("The argument 'bree' is required."); //$NON-NLS-1$
		}
		IExecutionEnvironment environment = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(bree);
		if (environment == null) {
			return McpToolResult.error("Unknown execution environment '%s'. Known ones are %s.".formatted(bree, //$NON-NLS-1$
					String.join(", ", knownEnvironments()))); //$NON-NLS-1$
		}
		Pattern namePattern;
		try {
			namePattern = Globs.compile(args.getString("namePattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'namePattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		Set<String> named = names(arguments);
		if (named.isEmpty() && namePattern == null) {
			return McpToolResult
					.error("Select projects with 'projects' or 'namePattern'; refusing to act on every plug-in."); //$NON-NLS-1$
		}
		String currentBree = args.getString("currentBree"); //$NON-NLS-1$
		boolean updateCompliance = args.getBoolean("updateCompliance", true); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$

		// the bundle is lazily activated, so its own context only exists once it started
		BundleContext context = FrameworkUtil.getBundle(SetBreeTool.class).getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(IBundleProjectService.class).getBundleContext();
		}
		if (context == null) {
			return McpToolResult.error("Neither this bundle nor PDE is active, so the bundle project service cannot be reached."); //$NON-NLS-1$
		}
		ServiceReference<IBundleProjectService> reference = context.getServiceReference(IBundleProjectService.class);
		if (reference == null) {
			return McpToolResult.error("PDE does not offer its bundle project service in this IDE."); //$NON-NLS-1$
		}
		IBundleProjectService service = context.getService(reference);
		try {
			return run(service, environment, named, namePattern, currentBree, updateCompliance, dryRun, maxResults,
					monitor);
		} finally {
			context.ungetService(reference);
		}
	}

	private McpToolResult run(IBundleProjectService service, IExecutionEnvironment environment, Set<String> named,
			Pattern namePattern, String currentBree, boolean updateCompliance, boolean dryRun, int maxResults,
			IProgressMonitor monitor) throws McpToolException {
		JsonArray reported = new JsonArray();
		int changed = 0;
		int skipped = 0;
		int considered = 0;
		List<String> unknown = new ArrayList<>(named);

		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			if (!named.isEmpty() && !named.contains(project.getName())) {
				continue;
			}
			if (namePattern != null && !namePattern.matcher(project.getName()).matches()) {
				continue;
			}
			unknown.remove(project.getName());
			Outcome outcome = act(service, project, environment, currentBree, updateCompliance, dryRun, monitor);
			if (outcome == null) {
				continue;
			}
			considered++;
			if (outcome.changed()) {
				changed++;
			} else {
				skipped++;
			}
			if (reported.size() < maxResults) {
				reported.add(outcome.json());
			}
		}
		if (!unknown.isEmpty()) {
			return McpToolResult.error("No project named '%s' in this workspace.".formatted(unknown.get(0))); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("bree", environment.getId()) //$NON-NLS-1$
				.put("dryRun", dryRun) //$NON-NLS-1$
				.put("total", considered) //$NON-NLS-1$
				.put("changed", changed) //$NON-NLS-1$
				.put("skipped", skipped) //$NON-NLS-1$
				.put("truncated", considered > reported.size()) //$NON-NLS-1$
				.put("projects", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/** What happened to one project, and the JSON reported for it. */
	private record Outcome(JsonObject json, boolean changed) {
	}

	private static Outcome done(JsonObject entry) {
		return new Outcome(entry.put("changed", Boolean.TRUE).put("skippedBecause", null), true); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static Outcome skip(JsonObject entry, String because) {
		return new Outcome(entry.put("changed", Boolean.FALSE).put("skippedBecause", because), false); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Returns {@code null} when the project is not a plug-in at all, or was filtered out. */
	private Outcome act(IBundleProjectService service, IProject project, IExecutionEnvironment environment,
			String currentBree, boolean updateCompliance, boolean dryRun, IProgressMonitor monitor)
			throws McpToolException {
		try {
			if (!project.isAccessible() || !project.hasNature(PLUGIN_NATURE)) {
				return null;
			}
		} catch (CoreException e) {
			return null;
		}
		IBundleProjectDescription description;
		try {
			description = service.getDescription(project);
		} catch (CoreException e) {
			throw new McpToolException("Could not read the bundle description of " + project.getName(), e); //$NON-NLS-1$
		}
		String[] previous = description.getExecutionEnvironments();
		String previousBree = previous == null || previous.length == 0 ? null : previous[0];
		if (currentBree != null && !currentBree.equals(previousBree)) {
			return null;
		}

		IJavaProject javaProject = JavaCore.create(project);
		String previousContainer = jreContainer(javaProject);
		JsonObject entry = new JsonObject().put("name", project.getName()) //$NON-NLS-1$
				.put("previousBree", previousBree) //$NON-NLS-1$
				.put("bree", environment.getId()) //$NON-NLS-1$
				.put("previousJreContainer", previousContainer); //$NON-NLS-1$

		Map<String, String> complianceBefore = optionsOf(javaProject);
		Map<String, String> complianceAfter = environment.getComplianceOptions();
		String wantedContainer = JavaRuntime.newJREContainerPath(environment).toString();
		boolean breeDiffers = !environment.getId().equals(previousBree);
		boolean containerDiffers = javaProject.exists() && !wantedContainer.equals(previousContainer);
		boolean complianceDiffers = updateCompliance && complianceAfter != null
				&& !agrees(complianceBefore, complianceAfter);

		if (!breeDiffers && !containerDiffers && !complianceDiffers) {
			return skip(entry, "It already declares %s, its JRE container follows it and its compiler settings agree." //$NON-NLS-1$
					.formatted(environment.getId()));
		}
		entry.put("compliance", complianceReport(complianceBefore, complianceAfter, updateCompliance)); //$NON-NLS-1$

		if (dryRun) {
			return done(entry.put("jreContainer", javaProject.exists() ? wantedContainer : null)); //$NON-NLS-1$
		}
		try {
			if (breeDiffers) {
				description.setExecutionEnvironments(new String[] { environment.getId() });
				description.apply(monitor);
			}
			// apply() writes the manifest header but leaves .classpath alone
			if (containerDiffers) {
				setJreContainer(javaProject, environment, monitor);
			}
			if (updateCompliance && complianceAfter != null) {
				Map<String, String> options = javaProject.getOptions(false);
				options.putAll(complianceAfter);
				javaProject.setOptions(options);
			}
		} catch (CoreException e) {
			return skip(entry, "Eclipse refused: " + e.getMessage()); //$NON-NLS-1$
		}
		return done(entry.put("jreContainer", jreContainer(javaProject))); //$NON-NLS-1$
	}

	/**
	 * Points the JRE container at {@code environment}, appending one when there is
	 * none, and leaves every other entry as it is.
	 */
	private static void setJreContainer(IJavaProject javaProject, IExecutionEnvironment environment,
			IProgressMonitor monitor) throws CoreException {
		if (javaProject == null || !javaProject.exists()) {
			return;
		}
		IPath wanted = JavaRuntime.newJREContainerPath(environment);
		List<IClasspathEntry> entries = new ArrayList<>(List.of(javaProject.getRawClasspath()));
		boolean found = false;
		for (int i = 0; i < entries.size(); i++) {
			IClasspathEntry entry = entries.get(i);
			if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
					&& JavaRuntime.JRE_CONTAINER.equals(entry.getPath().segment(0))) {
				found = true;
				entries.set(i, JavaCore.newContainerEntry(wanted, entry.getAccessRules(), entry.getExtraAttributes(),
						entry.isExported()));
			}
		}
		if (!found) {
			entries.add(JavaCore.newContainerEntry(wanted));
		}
		javaProject.setRawClasspath(entries.toArray(IClasspathEntry[]::new), monitor);
	}

	/** The JRE container entry, which is the part of {@code .classpath} the BREE drives. */
	private static String jreContainer(IJavaProject javaProject) {
		if (javaProject == null || !javaProject.exists()) {
			return null;
		}
		try {
			for (IClasspathEntry classpathEntry : javaProject.getRawClasspath()) {
				IPath path = classpathEntry.getPath();
				if (classpathEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
						&& JavaRuntime.JRE_CONTAINER.equals(path.segment(0))) {
					return path.toString();
				}
			}
		} catch (CoreException e) {
			return null;
		}
		return null;
	}

	private static Map<String, String> optionsOf(IJavaProject javaProject) {
		Map<String, String> options = new LinkedHashMap<>();
		if (javaProject == null || !javaProject.exists()) {
			return options;
		}
		for (String key : COMPLIANCE_KEYS) {
			options.put(key, javaProject.getOption(key, true));
		}
		return options;
	}

	private static boolean agrees(Map<String, String> before, Map<String, String> after) {
		for (String key : COMPLIANCE_KEYS) {
			String wanted = after.get(key);
			if (wanted != null && !wanted.equals(before.get(key))) {
				return false;
			}
		}
		return true;
	}

	private static JsonObject complianceReport(Map<String, String> before, Map<String, String> after,
			boolean updateCompliance) {
		JsonObject report = new JsonObject();
		for (String key : COMPLIANCE_KEYS) {
			String from = before.get(key);
			String to = after == null ? null : after.get(key);
			report.put(shortName(key), new JsonObject().put("from", from) //$NON-NLS-1$
					.put("to", updateCompliance && to != null ? to : from)); //$NON-NLS-1$
		}
		return report;
	}

	/** The option keys are long and all share a prefix, which reads badly in a result. */
	private static String shortName(String option) {
		return switch (option) {
		case JavaCore.COMPILER_COMPLIANCE -> "compliance"; //$NON-NLS-1$
		case JavaCore.COMPILER_SOURCE -> "source"; //$NON-NLS-1$
		default -> "target"; //$NON-NLS-1$
		};
	}

	private static List<String> knownEnvironments() {
		List<String> ids = new ArrayList<>();
		for (IExecutionEnvironment environment : JavaRuntime.getExecutionEnvironmentsManager()
				.getExecutionEnvironments()) {
			ids.add(environment.getId());
		}
		ids.sort(null);
		return ids;
	}

	private static Set<String> names(Map<String, Object> arguments) {
		Set<String> names = new LinkedHashSet<>();
		if (arguments != null && arguments.get("projects") instanceof List<?> list) { //$NON-NLS-1$
			for (Object entry : list) {
				String name = String.valueOf(entry).trim();
				if (!name.isEmpty()) {
					names.add(name);
				}
			}
		}
		return names;
	}
}
