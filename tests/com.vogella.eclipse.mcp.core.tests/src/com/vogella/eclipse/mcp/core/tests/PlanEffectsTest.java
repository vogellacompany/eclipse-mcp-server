package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.CollectionResult;
import org.eclipse.equinox.p2.query.IQuery;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.PlanEffects;

/**
 * How a provisioning plan's side effects on installed software are classified, which decides
 * whether eclipse_install refuses.
 */
class PlanEffectsTest {

	@Test
	void aDependencySwapUnderANewFeatureIsReportedInEveryCategory() throws Exception {
		List<IInstallableUnit> installed = List.of(unit("com.github.mwiede.jsch", "2.28.7"),
				unit("org.objectweb.asm", "9.10.1"), unit("org.objectweb.asm.util", "9.10.1"));
		IProvisioningPlan plan = plan(installed,
				List.of(unit("theme.feature.group", "1.0.0"), unit("theme.bundle", "1.0.0"),
						unit("com.jcraft.jsch", "0.1.55"), unit("org.objectweb.asm", "9.11.0"),
						unit("org.objectweb.asm.util", "9.11.0")),
				List.of(unit("com.github.mwiede.jsch", "2.28.7"), unit("org.objectweb.asm.util", "9.10.1")));

		PlanEffects effects = PlanEffects.of(plan, List.of("theme.feature.group"));

		assertEquals(1, effects.replaced(), "asm.util moved to another version");
		assertEquals(1, effects.removed(), "mwiede jsch was dropped");
		assertEquals(1, effects.addedBeside(), "asm 9.11.0 went in beside 9.10.1");
		assertTrue(effects.removesOrDuplicates());
		Map<String, Object> json = TestFixture.parse(effects.json().toString());
		// jcraft jsch and the theme bundle; the requested feature itself is not a side effect
		assertEquals(2, ((Number) json.get("newUnits")).intValue(), json.toString());
		assertEquals(3, ((Number) json.get("installedUnitsChanged")).intValue(), json.toString());
	}

	@Test
	void anUpgradeThatOnlyReplacesItsOwnBundlesDoesNotRemoveOrDuplicate() throws Exception {
		List<IInstallableUnit> installed = List.of(unit("tool.feature.group", "1.0.0"), unit("tool.bundle", "1.0.0"));
		IProvisioningPlan plan = plan(installed,
				List.of(unit("tool.feature.group", "2.0.0"), unit("tool.bundle", "2.0.0")), installed);

		PlanEffects effects = PlanEffects.of(plan, List.of("tool.feature.group"));

		assertEquals(1, effects.replaced());
		assertTrue(effects.changesInstalled());
		assertFalse(effects.removesOrDuplicates(), "the upgrade replaced only what it brings itself");
	}

	@Test
	void aNewUnitThatOnlyAddsChangesNothingInstalled() {
		IProvisioningPlan plan = plan(List.of(unit("platform", "1.0.0")),
				List.of(unit("theme.feature.group", "1.0.0"), unit("theme.bundle", "1.0.0")), List.of());

		assertFalse(PlanEffects.of(plan, List.of("theme.feature.group")).changesInstalled());
		assertFalse(PlanEffects.of(null, List.of("theme.feature.group")).changesInstalled());
	}

	private static IInstallableUnit unit(String id, String version) {
		InstallableUnitDescription description = new InstallableUnitDescription();
		description.setId(id);
		description.setVersion(Version.create(version));
		return MetadataFactory.createInstallableUnit(description);
	}

	@SuppressWarnings("unchecked")
	private static IProvisioningPlan plan(List<IInstallableUnit> installed, List<IInstallableUnit> additions,
			List<IInstallableUnit> removals) {
		IProfile profile = (IProfile) Proxy.newProxyInstance(PlanEffectsTest.class.getClassLoader(),
				new Class<?>[] { IProfile.class }, (proxy, method, args) -> switch (method.getName()) {
				case "query" -> ((IQuery<IInstallableUnit>) args[0]).perform(installed.iterator());
				default -> throw new UnsupportedOperationException(method.getName());
				});
		return (IProvisioningPlan) Proxy.newProxyInstance(PlanEffectsTest.class.getClassLoader(),
				new Class<?>[] { IProvisioningPlan.class }, (proxy, method, args) -> switch (method.getName()) {
				case "getAdditions" -> new CollectionResult<>(additions);
				case "getRemovals" -> new CollectionResult<>(removals);
				case "getProfile" -> profile;
				default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
