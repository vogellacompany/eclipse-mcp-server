package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.operations.Update;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.UpdateTool;

/** Which of the updates p2 offers eclipse_update selects for named units. */
class UpdateSelectionTest {

	@Test
	void onlyTheNewestUpdatePerNamedUnitIsSelected() {
		IInstallableUnit sdk = unit("org.eclipse.sdk.ide", "4.42.0.I20261012-2246");
		IInstallableUnit theme = unit("theme.feature.group", "1.0.0");
		Update[] possible = { new Update(sdk, unit("org.eclipse.sdk.ide", "4.42.0.I20261013-1052")),
				new Update(sdk, unit("org.eclipse.sdk.ide", "4.42.0.I20261013-2300")),
				new Update(theme, unit("theme.feature.group", "1.1.0")),
				new Update(sdk, unit("org.eclipse.sdk.ide", "4.42.0.I20261013-1534")),
				new Update(unit("other", "1.0.0"), unit("other", "2.0.0")) };

		List<Update> selected = UpdateTool.newestPerUnit(possible, List.of("org.eclipse.sdk.ide", "theme.feature.group"));

		Map<String, String> chosen = selected.stream().collect(Collectors.toMap(update -> update.toUpdate.getId(),
				update -> update.replacement.getVersion().toString()));
		assertEquals(Map.of("org.eclipse.sdk.ide", "4.42.0.I20261013-2300", "theme.feature.group", "1.1.0"), chosen);
	}

	@Test
	void nothingIsSelectedWithoutAnUpdateForANamedUnit() {
		assertTrue(UpdateTool.newestPerUnit(new Update[0], List.of("org.eclipse.sdk.ide")).isEmpty());
		Update[] other = { new Update(unit("other", "1.0.0"), unit("other", "2.0.0")) };
		assertTrue(UpdateTool.newestPerUnit(other, List.of("org.eclipse.sdk.ide")).isEmpty());
	}

	private static IInstallableUnit unit(String id, String version) {
		InstallableUnitDescription description = new InstallableUnitDescription();
		description.setId(id);
		description.setVersion(Version.create(version));
		return MetadataFactory.createInstallableUnit(description);
	}
}
