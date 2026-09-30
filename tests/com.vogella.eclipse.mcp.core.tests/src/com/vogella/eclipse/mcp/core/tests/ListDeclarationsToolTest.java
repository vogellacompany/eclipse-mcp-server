package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The declaration sweep and the registry cross-check behind it.
 * <p>
 * The fixture reproduces the shape the tool exists for: an extension point whose
 * {@code .exsd} declares an attribute java-typed, an extension naming a class
 * through it, and a class nothing in Java ever mentions.
 */
class ListDeclarationsToolTest {

	private static final String TOOL = "eclipse_list_declarations";

	private static final String PROJECT = "mcp-declarations-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aClassNamedThroughAJavaTypedSchemaAttributeIsLive() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Matcher");

		assertEquals("live-via-registry", entry.get("registryStatus"));
		Map<String, Object> evidence = firstEvidence(entry);
		assertEquals("plugin.xml", evidence.get("kind"));
		assertEquals("class", evidence.get("schemaAttribute"));
		// reported exactly as the schema writes it, class:interface form included
		assertEquals("registry.AbstractMatcher:", evidence.get("basedOn"));
		assertEquals(Boolean.TRUE, evidence.get("basedOnSatisfied"));
		assertTrue(String.valueOf(evidence.get("xpathOrHeader")).endsWith("/matcher@class"),
				"the position should locate the attribute, got " + evidence.get("xpathOrHeader"));
	}

	@Test
	void anAttributeTheSchemaDoesNotCallJavaTypedKeepsNothingAlive() throws Exception {
		fixtureProject();
		// named in the same plugin.xml, as a label rather than as a class
		assertEquals("dead", declaration("registry.Mentioned").get("registryStatus"));
	}

	@Test
	void aNameInProseKeepsNothingAlive() throws Exception {
		fixtureProject();
		assertEquals("dead", declaration("registry.Unused").get("registryStatus"),
				"a class named only in a text file and a comment is not kept alive by it");
	}

	@Test
	void anUnsatisfiedBasedOnIsReportedButDoesNotDemote() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.WrongSupertype");

		// basedOn is single valued and several real schemas cannot express what they
		// mean with it: org.eclipse.ui.decorators names ILabelDecorator while every
		// lightweight decorator implements ILightweightLabelDecorator. So an
		// unsatisfied constraint is a flag for a person, never a demotion
		assertEquals("live-via-registry", entry.get("registryStatus"));
		assertEquals(Boolean.FALSE, firstEvidence(entry).get("basedOnSatisfied"),
				"the mismatch still has to be visible");
	}

	@Test
	void aClassSatisfiesABasedOnThatNamesItself() throws Exception {
		fixtureProject();
		// getAllSupertypes does not include the type, and a class named as its own
		// basedOn was reported unsatisfied against itself
		assertEquals(Boolean.TRUE, firstEvidence(declaration("registry.AbstractMatcher")).get("basedOnSatisfied"));
	}

	@Test
	void anExecutableExtensionFactoryIsNotCheckedAgainstWhatItProduces() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Factory");

		assertEquals("live-via-registry", entry.get("registryStatus"));
		assertNull(firstEvidence(entry).get("basedOnSatisfied"),
				"basedOn describes what the factory produces, so there is nothing to check against the factory");
	}

	@Test
	void aPositionNamingANestedTypeKeepsTheEnclosingTypeAlive() throws Exception {
		fixtureProject();
		Map<String, Object> outer = declaration("registry.Handlers");

		assertEquals("live-via-registry", outer.get("registryStatus"));
		// two nested classes at the same xpath are two positions, not one
		assertEquals(2, ((List<?>) outer.get("registryEvidence")).size(), "got " + outer);
		Map<String, Object> evidence = firstEvidence(outer);
		assertEquals("registry.Handlers$New", evidence.get("namedType"));
		// basedOn is checked against the nested type the position names, which is
		// the one that extends AbstractMatcher
		assertEquals(Boolean.TRUE, evidence.get("basedOnSatisfied"));

		Map<String, Object> nested = declaration("registry.Handlers$New");
		assertEquals("live-via-registry", nested.get("registryStatus"));
		assertNull(firstEvidence(nested).get("namedType"));
	}

	@Test
	void aTypeTestIsReportedWithoutChangingTheVerdict() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Tested");

		assertEquals("dead", entry.get("registryStatus"), "an instanceof test is not instantiation");
		assertNotNull(entry.get("typeTests"), "but deleting it breaks the expression silently, so it is reported");
		assertNull(entry.get("registryEvidence"), "a type test is not registry evidence");
	}

	@Test
	void nothingReportedDeadCarriesRegistryEvidence() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "status", "dead", "maxResults", 5000));
		for (Object entry : declarations(result)) {
			assertNull(((Map<?, ?>) entry).get("registryEvidence"),
					"a class named in a registry position cannot be dead, got " + entry);
		}
	}

	@Test
	void theBundleActivatorIsLive() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Activator");
		assertEquals("live-via-registry", entry.get("registryStatus"));
		assertEquals("Bundle-Activator", firstEvidence(entry).get("xpathOrHeader"));
		// BundleActivator is not on this fixture's build path, so the constraint cannot
		// be checked. Unverifiable must not read as refuted, or every activator in a
		// project that does not compile against OSGi would be reported dead
		assertNull(firstEvidence(entry).get("basedOnSatisfied"));
	}

	@Test
	void aClassLoadedByALiteralNameIsLiveAndOneBuiltAtRuntimeIsACaveat() throws Exception {
		fixtureProject();
		assertEquals("live-via-registry", declaration("registry.Reflected").get("registryStatus"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));
		assertNotNull(result.get("dynamicReflectionSites"), "the concatenated load should be reported");
		assertTrue(String.join(" ", strings(result, "caveats")).contains("provisional"),
				"a dead verdict next to unresolvable reflection has to be reported as provisional");
	}

	@Test
	void membersOfALiveTypeAreUndecidableRatherThanDead() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "kinds", List.of("methods")));
		Map<String, Object> matches = find(result, "registry.Matcher#matches");
		assertEquals("undecidable", matches.get("registryStatus"),
				"the framework holds the instance and calls it, which no declaration list can see");
	}

	@Test
	void binaryTypesAreNeverListed() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));
		for (Object entry : declarations(result)) {
			String name = String.valueOf(((Map<?, ?>) entry).get("name"));
			assertFalse(name.startsWith("java."),
					"the JRE is on the build path, and none of it may reach the declaration list");
		}
	}

	@Test
	void aClassNamedByAnE4ApplicationModelIsLive() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Addon");

		assertEquals("live-via-registry", entry.get("registryStatus"), "got " + entry);
		assertEquals("e4xmi", firstEvidence(entry).get("kind"));
		assertTrue(String.valueOf(firstEvidence(entry).get("file")).endsWith(".e4xmi"), "got " + entry);
	}

	@Test
	@SuppressWarnings("unchecked")
	void ignoresARegistrationInsideBuildOutput() throws Exception {
		fixtureProject();
		IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
		project.getFolder("target").create(false, true, new NullProgressMonitor());
		// a Tycho product build copies the whole application model into its output,
		// and none of it is marked derived
		write(project, "target/LegacyIDE.e4xmi", """
				<application:Application xmlns:application="http://www.eclipse.org/ui/2010/UIModel/application">
				  <addons contributionURI="bundleclass://registry.host/registry.Addon"/>
				</application:Application>
				""");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		List<Object> evidence = (List<Object>) declaration("registry.Addon").get("registryEvidence");

		assertEquals(1, evidence.size(), "the copy under target must not count as a second position, got " + evidence);
	}

	@Test
	void theApiTierSaysWhatAWorkspaceSearchCanProve() throws Exception {
		fixtureProject();
		Map<String, Object> all = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));

		// exported plainly: consumers may exist anywhere, so no search settles it
		assertEquals("public-api", find(all, "published.Api").get("apiTier"));
		assertEquals(Boolean.FALSE, find(all, "published.Api").get("searchIsAuthoritative"));

		// not exported at all: there is nowhere else a reference could be
		Map<String, Object> hidden = find(all, "hidden.NotExported");
		assertEquals("not-exported", hidden.get("apiTier"));
		assertEquals(Boolean.TRUE, hidden.get("searchIsAuthoritative"));

		// x-internal declares that no bundle should use the package at all, which is a
		// stronger statement than an enumerable x-friends list, so it is authoritative too
		assertEquals("internal-api", find(all, "registry.Unused").get("apiTier"));
		assertEquals(Boolean.TRUE, find(all, "registry.Unused").get("searchIsAuthoritative"));
	}

	@Test
	void everyMemberCarriesTheApiTierOfItsPackage() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "kinds", List.of("types", "methods", "fields"), "maxResults", 5000));

		List<?> all = declarations(result);
		assertTrue(all.size() > 2, "the fixture should declare several members, got " + all.size());
		for (Object entry : all) {
			Map<?, ?> map = (Map<?, ?>) entry;
			assertNotNull(map.get("apiTier"), "no apiTier on " + map);
			assertNotNull(map.get("searchIsAuthoritative"), "no searchIsAuthoritative on " + map);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void anXFriendsPackageIsAuthoritativeWhenEveryFriendIsInTheWorkspace() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("friendly.ForFriends");

		assertEquals("internal-api-friends", entry.get("apiTier"));
		assertTrue(((List<String>) entry.get("friends")).contains(PROJECT));
		// the friend list names every bundle allowed to reference the package, and it
		// is in this workspace, so a zero result is proof rather than evidence
		assertEquals(Boolean.TRUE, entry.get("searchIsAuthoritative"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void apiToolsRestrictionTagsAreReported() throws Exception {
		fixtureProject();
		List<String> tags = (List<String>) declaration("hidden.NotExported").get("apiRestrictions");
		assertNotNull(tags, "the @noreference tag should be reported");
		assertTrue(tags.contains("noreference"), "got " + tags);
	}

	@Test
	void visibilityFilters() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "visibility", List.of("private")));
		assertEquals(0, declarations(result).size(), "every fixture type is public");
	}

	// --- fixture ----------------------------------------------------------

	private void fixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IProject project = javaProject.getProject();

		TestFixture.addType(javaProject, "registry", "AbstractMatcher",
				"package registry;\npublic abstract class AbstractMatcher {\n  public abstract boolean matches();\n}\n");
		TestFixture.addType(javaProject, "registry", "Matcher",
				"package registry;\npublic class Matcher extends AbstractMatcher {\n  @Override public boolean matches() { return true; }\n}\n");
		TestFixture.addType(javaProject, "registry", "WrongSupertype",
				"package registry;\npublic class WrongSupertype {\n}\n");
		TestFixture.addType(javaProject, "registry", "Mentioned",
				"package registry;\npublic class Mentioned {\n}\n");
		TestFixture.addType(javaProject, "registry", "Unused",
				"package registry;\n// registry.Unused is named here in a comment, which counts for nothing\npublic class Unused {\n}\n");
		TestFixture.addType(javaProject, "registry", "Activator",
				"package registry;\npublic class Activator {\n}\n");
		// declared in the fixture so the factory rule is actually exercised: without it
		// on the build path the check would fall through the unresolvable branch and
		// pass for the wrong reason
		TestFixture.addType(javaProject, "org.eclipse.core.runtime", "IExecutableExtensionFactory",
				"package org.eclipse.core.runtime;\npublic interface IExecutableExtensionFactory {\n  Object create();\n}\n");
		TestFixture.addType(javaProject, "registry", "Factory",
				"package registry;\npublic class Factory implements org.eclipse.core.runtime.IExecutableExtensionFactory {\n  @Override public Object create() { return null; }\n}\n");
		// registered through its nested classes only, the way WizardHandler$New is
		TestFixture.addType(javaProject, "registry", "Handlers",
				"package registry;\npublic class Handlers {\n  public static class New extends AbstractMatcher {\n    @Override public boolean matches() { return true; }\n  }\n  public static class Import extends AbstractMatcher {\n    @Override public boolean matches() { return true; }\n  }\n}\n");
		TestFixture.addType(javaProject, "registry", "Tested",
				"package registry;\npublic class Tested {\n}\n");
		TestFixture.addType(javaProject, "registry", "Reflected",
				"package registry;\npublic class Reflected {\n}\n");
		TestFixture.addType(javaProject, "registry", "Loader",
				"""
						package registry;
						public class Loader {
						  public Class<?> literal() throws Exception { return Class.forName("registry.Reflected"); }
						  public Class<?> built(String suffix) throws Exception { return Class.forName("registry." + suffix); }
						}
						""");

		TestFixture.addType(javaProject, "published", "Api",
				"package published;\npublic class Api {\n}\n");
		TestFixture.addType(javaProject, "friendly", "ForFriends",
				"package friendly;\npublic class ForFriends {\n}\n");
		TestFixture.addType(javaProject, "hidden", "NotExported",
				"package hidden;\n/**\n * @noreference\n */\npublic class NotExported {\n}\n");

		TestFixture.addType(javaProject, "registry", "Addon",
				"package registry;\npublic class Addon {\n}\n");
		// an e4 application model: nothing in Java refers to this class, the workbench
		// instantiates it at every start, and a sweep that cannot see it deletes an
		// addon the IDE needs and still compiles
		write(project, "LegacyIDE.e4xmi", """
				<?xml version="1.0" encoding="ASCII"?>
				<application:Application xmlns:application="http://www.eclipse.org/ui/2010/UIModel/application">
				  <addons xmi:id="_1" elementId="registry.addon"
				     contributionURI="bundleclass://registry.host/registry.Addon"/>
				</application:Application>
				""");

		write(project, "notes.txt", "registry.Unused is mentioned in this file, which is not a registry position.");

		IFolder metaInf = project.getFolder("META-INF");
		metaInf.create(false, true, new NullProgressMonitor());
		write(project, "META-INF/MANIFEST.MF", """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-SymbolicName: registry.host;singleton:=true
				Bundle-Version: 1.0.0
				Bundle-Activator: registry.Activator
				Export-Package: registry;x-internal:=true,
				 published,
				 friendly;x-friends:="mcp-declarations-test"
				""");

		IFolder schema = project.getFolder("schema");
		schema.create(false, true, new NullProgressMonitor());
		write(project, "schema/matchers.exsd", """
				<?xml version='1.0' encoding='UTF-8'?>
				<schema targetNamespace="registry.host">
				   <element name="matcher">
				      <complexType>
				         <attribute name="class" type="string" use="required">
				            <annotation>
				               <appInfo>
				                  <meta.attribute kind="java" basedOn="registry.AbstractMatcher:"/>
				               </appInfo>
				            </annotation>
				         </attribute>
				         <attribute name="label" type="string"/>
				      </complexType>
				   </element>
				</schema>
				""");

		write(project, "plugin.xml", """
				<?xml version="1.0" encoding="UTF-8"?>
				<?eclipse version="3.4"?>
				<plugin>
				   <extension-point id="matchers" name="Matchers" schema="schema/matchers.exsd"/>
				   <extension point="registry.host.matchers">
				      <matcher class="registry.Matcher" label="registry.Mentioned"/>
				      <matcher class="registry.WrongSupertype"/>
				      <matcher class="registry.AbstractMatcher"/>
				      <matcher class="registry.Factory:someProduct"/>
				      <matcher class="registry.Handlers$New"/>
				      <matcher class="registry.Handlers$Import"/>
				      <matcher class="registry.Matcher">
				         <enablement>
				            <instanceof value="registry.Tested"/>
				         </enablement>
				      </matcher>
				   </extension>
				</plugin>
				""");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
	}

	private static void write(IProject project, String path, String content) throws Exception {
		IFile file = project.getFile(path);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
	}

	// --- reading the answer -----------------------------------------------

	private static Map<String, Object> declaration(String name) throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));
		return find(result, name);
	}

	private static Map<String, Object> find(Map<String, Object> result, String name) {
		for (Object entry : declarations(result)) {
			if (entry instanceof Map<?, ?> map && name.equals(map.get("name"))) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) map;
				return typed;
			}
		}
		throw new AssertionError("No declaration named %s in %s".formatted(name, result));
	}

	private static Map<String, Object> firstEvidence(Map<String, Object> entry) {
		Object evidence = entry.get("registryEvidence");
		assertNotNull(evidence, "expected registry evidence on " + entry);
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) ((List<?>) evidence).get(0);
		return first;
	}

	private static List<?> declarations(Map<String, Object> result) {
		return (List<?>) result.get("declarations");
	}

	private static List<String> strings(Map<String, Object> result, String key) {
		List<String> values = new ArrayList<>();
		for (Object value : (List<?>) result.get(key)) {
			values.add(String.valueOf(value));
		}
		return values;
	}
}
