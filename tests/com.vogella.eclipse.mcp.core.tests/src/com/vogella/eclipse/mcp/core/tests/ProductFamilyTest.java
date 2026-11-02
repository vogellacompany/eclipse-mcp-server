package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.ProductFamily;

class ProductFamilyTest {

	@Test
	void aProductNamesItsVendorsPackages() {
		ProductFamily family = ProductFamily.of(List.of(), "DBeaver", "org.jkiss.dbeaver.core.product",
				"org.jkiss.dbeaver.ui.app.standalone.standalone", null);

		assertEquals("DBeaver", family.label());
		assertEquals(List.of("org.jkiss."), family.prefixes());
		assertEquals("running product", family.source());
	}

	@Test
	void anEclipseProductNamesNothing() {
		assertNull(ProductFamily.of(List.of(), "Eclipse SDK", "org.eclipse.sdk.ide", "org.eclipse.ui.ide.workbench",
				"com.vogella.eclipse.mcp.product"));
	}

	@Test
	void callersPrefixesAreAddedAndMatchWholeSegments() {
		ProductFamily family = ProductFamily.of(List.of(" com.dbeaver", "org.other."), "DBeaver", "org.jkiss.dbeaver.core.product");

		assertEquals(List.of("org.jkiss.", "com.dbeaver.", "org.other."), family.prefixes());
		assertEquals("running product and productPrefixes", family.source());
	}

	@Test
	void aRecordingOfAnotherJvmHasOnlyWhatTheCallerGave() {
		assertNull(ProductFamily.of(Map.of(), false));
		ProductFamily family = ProductFamily.of(Map.of("productPrefixes", List.of("org.jkiss")), false);

		assertEquals("Application", family.label());
		assertEquals(List.of("org.jkiss."), family.prefixes());
		assertEquals("productPrefixes", family.source());
	}
}
