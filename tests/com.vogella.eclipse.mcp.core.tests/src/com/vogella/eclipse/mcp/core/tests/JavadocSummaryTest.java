package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.jdt.internal.JavadocSummary;

class JavadocSummaryTest {

	@Test
	void keepsTheFirstSentenceOnly() {
		assertEquals("Returns the value.", JavadocSummary.of("/**\n * Returns the value. More text.\n */"));
	}

	@Test
	void keepsGenericsInsideCodeTagsAndDropsHtml() {
		assertEquals("A List<String> of names.",
				JavadocSummary.of("/** A <i>{@code List<String>}</i> of <b>names</b>. */"));
	}

	@Test
	void readsALinkAsItsLabelOrTarget() {
		assertEquals("Opens the editor or close.", JavadocSummary.of("/** Opens the {@link Editor editor} or {@link #close}. */"));
		assertEquals("See java.util.List.", JavadocSummary.of("/** See {@link java.util.List}. */"));
	}

	@Test
	void doesNotEndAtAnAbbreviation() {
		assertEquals("Takes a name, e.g. \"x\", i.e. any text.",
				JavadocSummary.of("/** Takes a name, e.g. \"x\", i.e. any text. Then more. */"));
	}

	@Test
	void stopsAtTheFirstBlockTag() {
		assertEquals("No full stop", JavadocSummary.of("/**\n * No full stop\n * @param x the x\n */"));
		assertNull(JavadocSummary.of("/**\n * @param x the x\n */"));
	}

	@Test
	void reportsAnInheritedComment() {
		assertEquals(JavadocSummary.INHERITED, JavadocSummary.of("/** {@inheritDoc} */"));
		assertEquals("Also closes the stream.", JavadocSummary.of("/** {@inheritDoc} Also closes the stream. */"));
	}

	@Test
	void keepsComparisonsThatAreNoHtml() {
		assertEquals("True when a < b and c > d.", JavadocSummary.of("/** True when a < b and c > d. */"));
	}

	@Test
	void anEmptyCommentHasNoSummary() {
		assertNull(JavadocSummary.of("/** */"));
		assertNull(JavadocSummary.of("/**\n *\n */"));
	}

	@Test
	void joinsASentenceOverSeveralLines() {
		assertEquals("Reads the whole file into memory.",
				JavadocSummary.of("/**\n * Reads the whole\n * file into memory. Then closes it.\n */"));
	}

	@Test
	void anAnnotationInsidePreIsNoBlockTag() {
		assertEquals("Use it as @Inject Foo foo; anywhere.",
				JavadocSummary.of("/**\n * Use it as <pre>\n * @Inject Foo foo;\n * </pre> anywhere.\n * @see Foo\n */"));
	}

	@Test
	void capsAVeryLongSentence() {
		String summary = JavadocSummary.of("/** " + "word ".repeat(100) + "end. */");
		assertEquals(200, summary.length());
	}
}
