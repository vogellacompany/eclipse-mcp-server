package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Searches the text of workspace files.
 */
public final class SearchTextTool implements IMcpTool {

	private static final int MAX_LINE_LENGTH = 500;

	@Override
	public String getName() {
		return "eclipse_search_text"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Searches the text of workspace files, including the ones the Java model cannot see: plugin.xml, .exsd schemas, .project, manifests, properties. Read-only. For Java elements eclipse_find_references answers better, because it resolves overloads and inheritance and this does not; this is for everything that is not Java, and for a client that has no filesystem of its own because the IDE is on another machine or hidden. It searches through Eclipse's own text search, so resources Eclipse marks derived are excluded by default rather than returned as duplicate noise, and fileNamePattern narrows to the files that matter. Maven and Gradle output under target and build is NOT marked derived and is therefore not covered by that; use excludePathPattern, for example */target/*, to drop it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["pattern"],
				  "properties": {
				    "pattern":         {"type":"string","description":"Text to find, or a regular expression when isRegex is true."},
				    "isRegex":         {"type":"boolean","default":false,"description":"Read pattern as a Java regular expression instead of as literal text."},
				    "isCaseSensitive": {"type":"boolean","default":false},
				    "projects":        {"type":"array","items":{"type":"string"},"description":"Restrict to these projects. Omit for the whole workspace."},
				    "path":            {"type":"string","description":"Restrict to this workspace folder or file, e.g. /app/src."},
				    "fileNamePattern": {"type":"string","description":"Glob over file names, e.g. *.exsd or plugin.xml. Omit for every file."},
				    "excludePathPattern": {"type":"string","description":"Glob over the workspace path, matches are skipped, e.g. */target/* to drop Maven build output. Maven and Gradle output is not marked derived, so includeDerived does not exclude it."},
				    "refresh":         {"type":"boolean","default":true,"description":"Read changes made outside the IDE into the searched scope first. Without it a file created outside the IDE is not searched and a deleted one still is."},
				    "includeDerived":  {"type":"boolean","default":false,"description":"Include derived resources, which is build output."},
				    "maxResults":      {"type":"integer","default":200,"minimum":1,"maximum":5000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String text = args.getString("pattern"); //$NON-NLS-1$
		if (text == null) {
			return McpToolResult.error("The argument 'pattern' is required."); //$NON-NLS-1$
		}
		boolean isRegex = args.getBoolean("isRegex", false); //$NON-NLS-1$
		boolean caseSensitive = args.getBoolean("isCaseSensitive", false); //$NON-NLS-1$
		boolean includeDerived = args.getBoolean("includeDerived", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 5000); //$NON-NLS-1$

		Pattern pattern;
		try {
			pattern = TextSearchEngine.createPattern(text, caseSensitive, isRegex);
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'pattern' as a regular expression: " + e.getMessage()); //$NON-NLS-1$
		}
		Pattern excluded;
		try {
			excluded = Globs.compile(args.getString("excludePathPattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'excludePathPattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		Pattern fileNames;
		try {
			fileNames = Globs.compile(args.getString("fileNamePattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'fileNamePattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}

		List<IResource> roots = new ArrayList<>();
		String path = args.getString("path"); //$NON-NLS-1$
		if (path != null) {
			IResource resource = ResourcesPlugin.getWorkspace().getRoot()
					.findMember(IPath.fromPortableString(path));
			if (resource == null) {
				return McpToolResult.error("No workspace resource at '%s'.".formatted(path)); //$NON-NLS-1$
			}
			roots.add(resource);
		}
		for (String name : ProjectSelection.names(arguments)) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
			roots.add(project);
		}
		if (roots.isEmpty()) {
			roots.add(ResourcesPlugin.getWorkspace().getRoot());
		}

		// the traversal set comes from the resource tree, so a file created outside
		// the IDE is invisible to the search and a deleted one is searched as a ghost.
		// The scope is what has to be refreshed, not the files it finds
		if (args.getBoolean("refresh", true)) { //$NON-NLS-1$
			for (IResource root : roots) {
				try {
					WorkspaceSync.refresh(root, monitor);
				} catch (org.eclipse.core.runtime.CoreException e) {
					// an unrefreshable scope is still searchable, and stale beats nothing
				}
			}
		}
		Collector collector = new Collector(maxResults, excluded);
		// the scope matches every file name against this, so "no filter" has to be a
		// pattern that matches rather than null, which it dereferences
		TextSearchScope scope = TextSearchScope.newSearchScope(roots.toArray(IResource[]::new),
				fileNames == null ? Pattern.compile(".*") : fileNames, includeDerived); //$NON-NLS-1$
		TextSearchEngine.createDefault().search(scope, collector, pattern, monitor);

		JsonObject result = new JsonObject().put("pattern", text) //$NON-NLS-1$
				.put("isRegex", Boolean.valueOf(isRegex)) //$NON-NLS-1$
				.put("isCaseSensitive", Boolean.valueOf(caseSensitive)) //$NON-NLS-1$
				.put("includeDerived", Boolean.valueOf(includeDerived)) //$NON-NLS-1$
				.put("total", Integer.valueOf(collector.distinct.size())) //$NON-NLS-1$
				.put("files", Integer.valueOf(collector.locations.size())) //$NON-NLS-1$
				.put("duplicatePathsCollapsed", Integer.valueOf(collector.collapsed)) //$NON-NLS-1$
				.put("excludedByPath", Integer.valueOf(collector.excludedMatches)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(collector.distinct.size() > maxResults)) //$NON-NLS-1$
				.put("matches", collector.matches(maxResults)); //$NON-NLS-1$
		if (collector.collapsed > 0) {
			result.put("note", //$NON-NLS-1$
					"%d further workspace path(s) reach the same file on disk and were collapsed. Nested projects make one file reachable through several paths, and counting those separately inflates a result in a way a client cannot detect without a filesystem. alsoVisibleAs lists the other paths." //$NON-NLS-1$
							.formatted(Integer.valueOf(collector.collapsed)));
		}
		return McpToolResult.of(result.toString());
	}

	/**
	 * Reports one line per match. The line is found by walking out from the match
	 * rather than by splitting the file.
	 */
	private static final class Collector extends TextSearchRequestor {

		/** Keyed by physical location and offset, so one file counts once; matches past the cap map to null. */
		private final Map<String, JsonObject> distinct = new LinkedHashMap<>();

		private final Map<String, JsonArray> alsoVisibleAs = new HashMap<>();

		private final Set<String> locations = new LinkedHashSet<>();

		private final int maxResults;

		private final Pattern excluded;

		private int collapsed;

		private int excludedMatches;

		private IFile currentFile;

		private int[] lineStarts;

		Collector(int maxResults, Pattern excluded) {
			this.maxResults = maxResults;
			this.excluded = excluded;
		}

		@Override
		public boolean acceptFile(IFile file) {
			currentFile = null;
			lineStarts = null;
			return true;
		}

		@Override
		public boolean reportBinaryFile(IFile file) {
			return false;
		}

		@Override
		public boolean canRunInParallel() {
			// the counters and the line cache below are not synchronised, and a text
			// search is IO bound anyway
			return false;
		}

		@Override
		public boolean acceptPatternMatch(TextSearchMatchAccess match) {
			IFile file = match.getFile();
			String path = file.getFullPath().toString();
			if (excluded != null && excluded.matcher(path).matches()) {
				excludedMatches++;
				return true;
			}
			int offset = match.getMatchOffset();
			// 754 of 755 projects in a platform workspace are nested inside another
			// one, so a single file on disk is reachable through several workspace
			// paths and the same match arrives once per path. Counting those as
			// separate results inflates the answer by an unpredictable factor, and a
			// client cannot see it without a filesystem, which is what this tool
			// exists to do without
			String location = file.getLocationURI() == null ? path : file.getLocationURI().toString();
			locations.add(location);
			String key = location + "@" + offset; //$NON-NLS-1$
			if (distinct.containsKey(key)) {
				collapsed++;
				if (distinct.get(key) != null) {
					alsoVisibleAs.computeIfAbsent(key, ignored -> new JsonArray()).add(path);
				}
				return true;
			}
			if (distinct.size() >= maxResults) {
				// only counted: the answer carries maxResults matches, the rest just feeds total
				distinct.put(key, null);
				return true;
			}
			int start = offset;
			while (start > 0 && match.getFileContentChar(start - 1) != '\n') {
				start--;
			}
			int length = match.getFileContentLength();
			int end = offset + match.getMatchLength();
			while (end < length && match.getFileContentChar(end) != '\n') {
				end++;
			}
			String line = match.getFileContent(start, Math.min(end, length) - start).stripTrailing();
			distinct.put(key, new JsonObject().put("file", path) //$NON-NLS-1$
					.put("line", Integer.valueOf(lineOf(match, offset))) //$NON-NLS-1$
					.put("offset", Integer.valueOf(offset)) //$NON-NLS-1$
					.put("text", line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line)); //$NON-NLS-1$
			return true;
		}

		JsonArray matches(int limit) {
			JsonArray array = new JsonArray();
			for (Map.Entry<String, JsonObject> entry : distinct.entrySet()) {
				if (array.size() >= limit) {
					break;
				}
				JsonArray others = alsoVisibleAs.get(entry.getKey());
				if (others != null) {
					entry.getValue().put("alsoVisibleAs", others); //$NON-NLS-1$
				}
				array.add(entry.getValue());
			}
			return array;
		}

		/** Line starts are computed once per file, since matches arrive grouped by file. */
		private int lineOf(TextSearchMatchAccess match, int offset) {
			if (currentFile != match.getFile() || lineStarts == null) {
				currentFile = match.getFile();
				List<Integer> starts = new ArrayList<>();
				starts.add(Integer.valueOf(0));
				int length = match.getFileContentLength();
				for (int i = 0; i < length; i++) {
					if (match.getFileContentChar(i) == '\n') {
						starts.add(Integer.valueOf(i + 1));
					}
				}
				lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
			}
			int index = Arrays.binarySearch(lineStarts, offset);
			return index >= 0 ? index + 1 : -index - 1;
		}
	}
}
