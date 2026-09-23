package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.osgi.framework.Bundle;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports what is installed in this IDE and at which version.
 */
public final class GetInstallationTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 100;

	@Override
	public String getName() {
		return "eclipse_get_installation"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the product, the installed feature groups with their versions, the configuration timestamps this installation can be reverted to, and whether the bundles that ACTUALLY STARTED match what p2 records. Changes nothing. The feature list is the p2 profile's view, which is what p2 believes is installed rather than what the framework loaded; those are the same answer only while nothing has gone wrong, and when they differ the profile is the confident one and the wrong one. So 'runtime' compares every bundle p2 knows about against the live OSGi framework and reports each disagreement as profileSays versus actuallyRunning, because an update reported as landed while the IDE goes on running the previous build is the failure this tool exists to catch. It confirms that an install or an update actually landed, and answers 'which version of the MCP server is this IDE running', neither of which the other tools can: eclipse_check_for_updates only reports units that HAVE an update, so it says nothing at all when everything is current, and eclipse_get_bundle_info describes the active TARGET PLATFORM rather than the running installation, which looks like the same question and is not. Use 'filter' to narrow the feature list, because an SDK installs a lot of feature groups; it does not narrow runtime.mismatches, which names every diverging bundle whatever the filter. currentTimestamp is the one eclipse_update reports as previousConfiguration, so a caller can name what a revert would go back to."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "filter":     {"type":"string","description":"Only feature groups whose id or name contains this text, case insensitive, e.g. 'vogella' or 'jdt'. The total counts everything installed, not only what matched."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":2000},
				    "timestamps": {"type":"boolean","default":false,"description":"Also list the configuration timestamps that can be reverted to, newest first. Off by default because an IDE that is updated often has many."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String filter = args.getString("filter"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("This IDE has no provisioning agent, so it cannot report its installation."); //$NON-NLS-1$
		}
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		if (registry == null) {
			return McpToolResult.error("This IDE has no profile registry, so it cannot report its installation."); //$NON-NLS-1$
		}
		IProfile profile = registry.getProfile(IProfileRegistry.SELF);
		if (profile == null) {
			return McpToolResult.error(
					"This IDE has no self profile, which is normal for one started from a development launch rather than installed."); //$NON-NLS-1$
		}

		String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
		List<IInstallableUnit> matched = new ArrayList<>();
		int total = 0;
		for (IInstallableUnit unit : profile.query(QueryUtil.createIUGroupQuery(), monitor)) {
			total++;
			if (needle == null || contains(unit, needle)) {
				matched.add(unit);
			}
		}
		matched.sort((left, right) -> left.getId().compareToIgnoreCase(right.getId()));

		JsonArray features = new JsonArray();
		for (IInstallableUnit unit : matched) {
			if (features.size() >= maxResults) {
				break;
			}
			features.add(new JsonObject().put("id", unit.getId()) //$NON-NLS-1$
					.put("version", unit.getVersion().toString()) //$NON-NLS-1$
					.put("name", unit.getProperty(IInstallableUnit.PROP_NAME, null))); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("product", product()) //$NON-NLS-1$
				.put("profile", profile.getProfileId()) //$NON-NLS-1$
				.put("currentTimestamp", Long.valueOf(profile.getTimestamp())) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("matched", Integer.valueOf(matched.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(matched.size() > features.size())) //$NON-NLS-1$
				.put("features", features); //$NON-NLS-1$
		result.put("runtime", runtimeCheck(profile, monitor, maxResults)); //$NON-NLS-1$
		if (args.getBoolean("timestamps", false)) { //$NON-NLS-1$
			JsonArray stamps = new JsonArray();
			long[] values = registry.listProfileTimestamps(IProfileRegistry.SELF);
			if (values != null) {
				for (int i = values.length - 1; i >= 0; i--) {
					stamps.add(new JsonObject().put("timestamp", Long.valueOf(values[i])) //$NON-NLS-1$
							.put("when", new Date(values[i]).toString())); //$NON-NLS-1$
				}
			}
			result.put("revertPoints", stamps); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	/**
	 * What the framework actually started, compared against what the profile says.
	 * <p>
	 * Everything above this comes out of the p2 profile, which is what p2 BELIEVES
	 * is installed. That is the same question as what is running only while nothing
	 * has gone sideways, and when it has, the profile is the more confident of the
	 * two and the wrong one: it was reported as a landed update while the IDE went
	 * on running the previous build. A surrogate profile pointing at a deleted
	 * shared install did exactly that here for a day. A hot
	 * {@code eclipse_install_bundle}, a dropin and a bundle the reconciler refused
	 * all produce the same divergence for other reasons.
	 * <p>
	 * The live {@code BundleContext} cannot be wrong about this, so it is what
	 * decides. Bundles p2 does not know are skipped rather than reported as
	 * mismatches, since a dropin or a hot install is legitimately outside the
	 * profile and saying so for hundreds of them would bury the real ones.
	 */
	private static JsonObject runtimeCheck(IProfile profile, IProgressMonitor monitor, int maxResults) {
		Bundle self = org.osgi.framework.FrameworkUtil.getBundle(GetInstallationTool.class);
		org.osgi.framework.BundleContext context = self == null ? null : self.getBundleContext();
		if (context == null) {
			return new JsonObject().put("checked", Integer.valueOf(0)) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The running bundles could not be read, so everything above is the p2 profile's view alone and nothing here confirms the IDE is running it."); //$NON-NLS-1$
		}
		// EVERY version, not the first one found. An installation legitimately carries
		// several versions of a bundle, junit-jupiter-api 5 and 6 side by side here,
		// and the framework resolves one of them. Keeping a single version per id
		// reported each of those as a mismatch, which is nine false alarms out of
		// twelve on this machine and exactly the noise that makes a check unusable.
		Map<String, java.util.Set<String>> inProfile = new java.util.HashMap<>();
		for (IInstallableUnit unit : profile.query(QueryUtil.createIUAnyQuery(), monitor)) {
			// a bundle IU carries the symbolic name as its id, so this is the join
			inProfile.computeIfAbsent(unit.getId(), id -> new java.util.HashSet<>())
					.add(unit.getVersion().toString());
		}
		JsonArray mismatches = new JsonArray();
		int checked = 0;
		int diverged = 0;
		for (Bundle bundle : context.getBundles()) {
			String name = bundle.getSymbolicName();
			if (name == null) {
				continue;
			}
			java.util.Set<String> expected = inProfile.get(name);
			if (expected == null) {
				continue;
			}
			checked++;
			String running = bundle.getVersion().toString();
			if (expected.contains(running)) {
				continue;
			}
			diverged++;
			// not narrowed by the feature filter: a count whose bundle cannot be named is
			// an alarm nobody can act on
			if (mismatches.size() < maxResults) {
				List<String> versions = new ArrayList<>(expected);
				versions.sort(null);
				mismatches.add(new JsonObject().put("bundle", name) //$NON-NLS-1$
						.put("profileSays", String.join(", ", versions)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("actuallyRunning", running)); //$NON-NLS-1$
			}
		}
		JsonObject runtime = new JsonObject().put("checked", Integer.valueOf(checked)) //$NON-NLS-1$
				.put("diverged", Integer.valueOf(diverged)) //$NON-NLS-1$
				.put("agrees", Boolean.valueOf(diverged == 0)) //$NON-NLS-1$
				.put("mismatches", mismatches) //$NON-NLS-1$
				.put("mismatchesTruncated", Boolean.valueOf(diverged > mismatches.size())); //$NON-NLS-1$
		if (diverged > 0) {
			runtime.put("warning", //$NON-NLS-1$
					"THE VERSIONS ABOVE ARE NOT ALL RUNNING. %d of the %d bundles p2 knows about are running a different version than the profile records, so a feature version listed above may describe an install that never took effect. actuallyRunning is the truth; profileSays is what p2 believes. Causes: an update whose bundles were written somewhere the framework does not load from, a hot eclipse_install_bundle, a dropin, or a bundle the reconciler refused. Compare against configuration/org.eclipse.equinox.simpleconfigurator/bundles.info, and restart with -clean if bundles.info is already correct." //$NON-NLS-1$
							.formatted(Integer.valueOf(diverged), Integer.valueOf(checked)));
		}
		return runtime;
	}

	private static boolean contains(IInstallableUnit unit, String needle) {
		if (unit.getId().toLowerCase(Locale.ROOT).contains(needle)) {
			return true;
		}
		String name = unit.getProperty(IInstallableUnit.PROP_NAME, null);
		return name != null && name.toLowerCase(Locale.ROOT).contains(needle);
	}

	/** The product this IDE runs, and the version of the bundle that defines it. */
	private static JsonObject product() {
		IProduct product = Platform.getProduct();
		if (product == null) {
			return null;
		}
		Bundle defining = product.getDefiningBundle();
		return new JsonObject().put("id", product.getId()) //$NON-NLS-1$
				.put("name", product.getName()) //$NON-NLS-1$
				.put("definingBundle", defining == null ? null : defining.getSymbolicName()) //$NON-NLS-1$
				.put("version", defining == null ? null : defining.getVersion().toString()); //$NON-NLS-1$
	}
}
