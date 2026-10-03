package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.basic.internal.PlatformLogFile;

class PlatformLogFileTest {

	@TempDir
	Path folder;

	/** A UI freeze as org.eclipse.ui.monitoring writes it: a multi status with one sample per child. */
	private static final String FREEZE = """
			!SESSION 2026-08-20 11:58:00.000 -----------------------------------------------
			!ENTRY org.eclipse.ui.monitoring 2 0 2026-08-20 11:58:27.932
			!MESSAGE UI freeze of 3.2s at 11:58:24.766
			!SUBENTRY 1 org.eclipse.ui.monitoring 1 0 2026-08-20 11:58:27.932
			!MESSAGE Sample at 11:58:26.099 (+1.333s)
			Thread 'main' tid=3 (RUNNABLE)
			!STACK 0
			Stack Trace
			\tat org.eclipse.jdt.internal.core.JavaModelManager.create(JavaModelManager.java:1028)
			\tat org.eclipse.jdt.core.JavaCore.create(JavaCore.java:3901)
			!SUBENTRY 1 org.eclipse.ui.monitoring 1 0 2026-08-20 11:58:27.932
			!MESSAGE Sample at 11:58:27.432 (+2.666s)
			!STACK 0
			Stack Trace
			\tat org.eclipse.swt.internal.gtk.GTK.gtk_main_iteration_do(Native Method)
			!ENTRY org.eclipse.jdt.core 4 4 2026-08-20 11:58:23.157
			!MESSAGE JavaBuilder handling CoreException while cleaning: JavaEclipseProject
			!STACK 1
			org.eclipse.core.runtime.CoreException: release 25 is not found in the system
			\tat org.eclipse.jdt.internal.core.builder.ClasspathJrtWithReleaseOption.<init>(ClasspathJrtWithReleaseOption.java:90)
			""";

	@Test
	void readsSeverityCodePluginAndTimestamp() throws Exception {
		List<PlatformLogFile.Entry> entries = read(FREEZE);

		assertEquals(2, entries.size());
		PlatformLogFile.Entry freeze = entries.get(0);
		assertEquals("org.eclipse.ui.monitoring", freeze.plugin());
		assertEquals(2, freeze.severity());
		assertEquals(0, freeze.code());
		assertEquals("2026-08-20T11:58:27.932", freeze.time().toString());
		assertEquals("UI freeze of 3.2s at 11:58:24.766", freeze.message());
	}

	@Test
	void keepsTheChildrenOfAMultiStatus() throws Exception {
		PlatformLogFile.Entry freeze = read(FREEZE).get(0);

		assertEquals(2, freeze.children().size());
		assertEquals("Sample at 11:58:26.099 (+1.333s)\nThread 'main' tid=3 (RUNNABLE)",
				freeze.children().get(0).message());
		assertEquals("Sample at 11:58:27.432 (+2.666s)", freeze.children().get(1).message());
	}

	@Test
	void keepsTheStackTraceOfEveryChild() throws Exception {
		PlatformLogFile.Entry freeze = read(FREEZE).get(0);

		String first = freeze.children().get(0).stackTrace();
		assertTrue(first.startsWith("Stack Trace\n\tat org.eclipse.jdt.internal.core.JavaModelManager.create"), first);
		assertTrue(first.endsWith("JavaCore.create(JavaCore.java:3901)"), first);
		assertTrue(freeze.children().get(1).stackTrace().contains("gtk_main_iteration_do"));
	}

	@Test
	void readsTheThrowableOfAnEntryThatCarriesOne() throws Exception {
		PlatformLogFile.Entry builder = read(FREEZE).get(1);

		assertEquals(4, builder.severity());
		assertEquals("org.eclipse.core.runtime.CoreException: release 25 is not found in the system",
				builder.exception());
		assertTrue(builder.stackTrace().contains("ClasspathJrtWithReleaseOption"));
		assertTrue(builder.children().isEmpty());
	}

	@Test
	void reportsNoThrowableForAFreeze() throws Exception {
		// "Stack Trace" is the throwable line the monitoring bundle writes, not a real exception
		assertNull(read(FREEZE).get(0).exception());
	}

	@Test
	void survivesAnEntryWithoutSeverityCodeOrStack() throws Exception {
		List<PlatformLogFile.Entry> entries = read("""
				!ENTRY com.example.thing 2026-08-20 11:58:27.932
				!MESSAGE Something happened
				""");

		assertEquals(1, entries.size());
		assertEquals("com.example.thing", entries.get(0).plugin());
		assertEquals("Something happened", entries.get(0).message());
		assertNull(entries.get(0).stackTrace());
	}

	@Test
	void nestsASubentryOfASubentry() throws Exception {
		List<PlatformLogFile.Entry> entries = read("""
				!ENTRY com.example.thing 4 0 2026-08-20 11:58:27.932
				!MESSAGE Top
				!SUBENTRY 1 com.example.thing 4 0 2026-08-20 11:58:27.932
				!MESSAGE Middle
				!SUBENTRY 2 com.example.thing 4 0 2026-08-20 11:58:27.932
				!MESSAGE Bottom
				!SUBENTRY 1 com.example.thing 4 0 2026-08-20 11:58:27.932
				!MESSAGE Second middle
				""");

		PlatformLogFile.Entry top = entries.get(0);
		assertEquals(2, top.children().size());
		assertEquals("Middle", top.children().get(0).message());
		assertEquals("Bottom", top.children().get(0).children().get(0).message());
		assertEquals("Second middle", top.children().get(1).message());
		assertTrue(top.children().get(1).children().isEmpty());
	}

	@Test
	void readsAnEmptyLogAsNoEntries() throws Exception {
		assertEquals(List.of(), read(""));
	}

	private List<PlatformLogFile.Entry> read(String content) throws Exception {
		Path file = folder.resolve("test.log");
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return PlatformLogFile.read(file);
	}
}
