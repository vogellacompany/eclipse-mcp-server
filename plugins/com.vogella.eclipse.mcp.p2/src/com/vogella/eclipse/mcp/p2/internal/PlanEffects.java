package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** What a resolved plan does to installed units, apart from the requested roots. */
public record PlanEffects(JsonObject json, int replaced, int removed, int addedBeside) {

	private static final int MAX_PLAN_ENTRIES = 50;

	public boolean changesInstalled() {
		return replaced + removed + addedBeside > 0;
	}

	/** A removed unit or a second version beside an installed one, which an upgrade does not explain. */
	public boolean removesOrDuplicates() {
		return removed + addedBeside > 0;
	}

	/**
	 * Lists the installed units a plan replaces, removes or installs a second version beside,
	 * leaving out {@code roots}.
	 */
	public static PlanEffects of(IProvisioningPlan plan, Collection<String> roots) {
		if (plan == null) {
			return new PlanEffects(null, 0, 0, 0);
		}
		Map<String, List<IInstallableUnit>> added = byId(plan.getAdditions());
		Map<String, List<IInstallableUnit>> removed = byId(plan.getRemovals());
		IProfile profile = plan.getProfile();
		JsonArray replaced = new JsonArray();
		JsonArray dropped = new JsonArray();
		JsonArray beside = new JsonArray();
		int replacedCount = 0;
		int removedCount = 0;
		int besideCount = 0;
		int newUnits = 0;
		for (Map.Entry<String, List<IInstallableUnit>> entry : removed.entrySet()) {
			if (roots.contains(entry.getKey())) {
				continue;
			}
			List<IInstallableUnit> replacements = added.get(entry.getKey());
			JsonObject item = new JsonObject().put("unit", entry.getKey()) //$NON-NLS-1$
					.put("fromVersion", versions(entry.getValue())); //$NON-NLS-1$
			if (replacements != null) {
				item.put("toVersion", versions(replacements)); //$NON-NLS-1$
			}
			JsonArray target = replacements == null ? dropped : replaced;
			if (target.size() < MAX_PLAN_ENTRIES) {
				target.add(item);
			}
			if (replacements == null) {
				removedCount++;
			} else {
				replacedCount++;
			}
		}
		for (Map.Entry<String, List<IInstallableUnit>> entry : added.entrySet()) {
			if (removed.containsKey(entry.getKey()) || roots.contains(entry.getKey())) {
				continue;
			}
			List<IInstallableUnit> installed = new ArrayList<>();
			if (profile != null) {
				profile.query(QueryUtil.createIUQuery(entry.getKey()), null).forEach(installed::add);
			}
			if (installed.isEmpty()) {
				newUnits++;
			} else {
				if (beside.size() < MAX_PLAN_ENTRIES) {
					beside.add(new JsonObject().put("unit", entry.getKey()) //$NON-NLS-1$
							.put("version", versions(entry.getValue())) //$NON-NLS-1$
							.put("installedVersion", versions(installed))); //$NON-NLS-1$
				}
				besideCount++;
			}
		}
		int changed = replacedCount + removedCount + besideCount;
		JsonObject json = new JsonObject().put("replaced", replaced) //$NON-NLS-1$
				.put("removed", dropped) //$NON-NLS-1$
				.put("addedBesideInstalled", beside) //$NON-NLS-1$
				.put("newUnits", Integer.valueOf(newUnits)) //$NON-NLS-1$
				.put("installedUnitsChanged", Integer.valueOf(changed)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(changed > replaced.size() + dropped.size() + beside.size())); //$NON-NLS-1$
		return new PlanEffects(json, replacedCount, removedCount, besideCount);
	}

	private static Map<String, List<IInstallableUnit>> byId(IQueryable<IInstallableUnit> units) {
		Map<String, List<IInstallableUnit>> byId = new TreeMap<>();
		units.query(QueryUtil.ALL_UNITS, null)
				.forEach(unit -> byId.computeIfAbsent(unit.getId(), id -> new ArrayList<>()).add(unit));
		return byId;
	}

	private static String versions(List<IInstallableUnit> units) {
		return String.join(", ", units.stream().map(unit -> unit.getVersion().toString()).toList()); //$NON-NLS-1$
	}
}
