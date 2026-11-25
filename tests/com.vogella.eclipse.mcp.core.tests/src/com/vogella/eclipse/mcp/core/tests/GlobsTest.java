package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.Globs;

class GlobsTest {

	@Test
	void keepsAnAbsentFilterAbsent() {
		assertNull(Globs.compile(null));
	}

	@Test
	void starMatchesAnyRunAndQuestionMarkOneCharacter() {
		Pattern pattern = Globs.compile("com.*.te?t");
		assertTrue(pattern.matcher("com.vogella.test").matches());
		assertTrue(pattern.matcher("com..text").matches());
		assertFalse(pattern.matcher("com.vogella.tet").matches());
		assertFalse(pattern.matcher("com.vogella.teest").matches());
	}

	@Test
	void treatsEveryOtherCharacterLiterally() {
		Pattern pattern = Globs.compile("a.b+(c)[d]$");
		assertTrue(pattern.matcher("a.b+(c)[d]$").matches());
		assertFalse(pattern.matcher("axb+(c)[d]$").matches());
		assertFalse(pattern.matcher("a.bb(c)d").matches());
	}

	@Test
	void ignoresCase() {
		assertTrue(Globs.compile("*Tool.JAVA").matcher("BuildTool.java").matches());
	}
}
