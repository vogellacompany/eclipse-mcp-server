package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds references to a Java type, method or field with the JDT search engine.
 */
public final class FindReferencesTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	private static final String ALL = "all"; //$NON-NLS-1$

	private static final String READ = "read"; //$NON-NLS-1$

	private static final String WRITE = "write"; //$NON-NLS-1$

	private static final Set<String> ACCESS_KINDS = Set.of(ALL, READ, WRITE);

	@Override
	public String getName() {
		return "eclipse_find_references"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds all references to a Java type, method or field across the workspace, using the JDT search engine. Far more accurate than a text search because it resolves overloads and inheritance. For fields it also splits the references into reads and writes, which decides whether a field is actually used: one written in four places and read in none is dead, though a text search sees four live occurrences. A field initializer counts as a write but is a declaration rather than a reference, so byKind need not sum to total; with accessKind 'write' the initializer IS among the matches, and it is marked with declaration true and counted separately in byKind, so 'is this ever assigned outside its own declaration', which is what decides whether a field can be final, is one call. Every match carries an origin of source or binary: a binary match is inside a compiled jar on some project's build path, not in anyone's source, and byOrigin counts the two separately. Judge 'how many consumers does this API have' from the source count. resolvedFrom names the jar or source folder the type was resolved from, and matchedBy says whether the search followed the compiled binding or matched the qualified name; a type that exists only in jars is searched by name, because binding to one copy of a library silently misses every reference compiled against another. Overloads are addressable: every match carries the signature of the overload it belongs to, byMember gives one count per overload, and paramTypes restricts the search to a single one, so 'is this overload dead while its sibling has sixteen callers' is answerable rather than merged into one number. One physical file reached through several projects that link it counts once: linkedDuplicates says how many were folded away and alsoIn names the other projects, because a source file shared by seven fragments otherwise multiplies every call site by seven."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",

				  "properties": {
				    "queries":    {"type":"array","description":"Ask about many elements in one call, which returns COUNTS ONLY: [{typeName, memberName?, accessKind?}]. A dead code sweep asks 'how many references' about hundreds of candidates, and one call per candidate is mostly round trips. Use the single form when you need the match locations.","items":{"type":"object","properties":{"typeName":{"type":"string"},"memberName":{"type":"string"},"paramTypes":{"type":"array","items":{"type":"string"}},"accessKind":{"type":"string","enum":["all","read","write"]}},"required":["typeName"],"additionalProperties":false}},
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer"},
				    "memberName": {"type":"string","description":"Optional method or field name. Omit to find references to the type itself. Every overload of the name is searched unless paramTypes narrows it, and each match reports the overload it belongs to."},
				    "paramTypes": {"type":"array","items":{"type":"string"},"description":"Selects one overload of memberName by its parameter types, simple or qualified: ['String','int','int'] or ['java.lang.String']. An empty array is the no-argument overload. Without it all overloads are searched and byMember splits the counts."},
				    "project":    {"type":"string","description":"Optional project used to resolve the type. It scopes the search to that project AND everything on its build path, which includes other workspace projects and jars, so it narrows less than it looks. Defaults to the whole workspace."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000},
				    "accessKind": {"type":"string","enum":["all","read","write"],"default":"all","description":"Restrict to read or to write accesses. Only meaningful for fields. With 'all', field results additionally carry a byKind summary and a kind per match. A field initializer is a write but not a reference, so byKind counts can exceed total. With 'read' or 'write' the matches include the declaration itself, marked with declaration true and counted under declaration in byKind."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		List<Map<String, Object>> queries = queries(arguments);
		if (!queries.isEmpty()) {
			return countAll(queries, args.getString("project"), monitor); //$NON-NLS-1$
		}
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("Give 'typeName', or 'queries' to ask about many elements at once."); //$NON-NLS-1$
		}
		String memberName = args.getString("memberName"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		String accessKind = args.getString("accessKind", ALL); //$NON-NLS-1$
		if (!ACCESS_KINDS.contains(accessKind)) {
			return McpToolResult.error("Unknown accessKind '%s', expected one of all, read, write.".formatted(accessKind)); //$NON-NLS-1$
		}
		List<String> paramTypes = paramTypes(arguments);
		if (paramTypes != null && memberName == null) {
			return McpToolResult.error("'paramTypes' picks one overload of 'memberName', so give a memberName too."); //$NON-NLS-1$
		}

		List<IJavaProject> projects;
		List<IType> types;
		IType type;
		List<Overload> overloads;
		List<IMember> fields;
		String resolved;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			JavaModelSupport.refresh(projectName, monitor);
			types = JavaModelSupport.findTypes(typeName, projects, monitor);
			type = types.get(0);
			overloads = memberName == null ? List.of() : overloadsOf(types, memberName, paramTypes);
			fields = fieldsOf(overloads);
			resolved = memberName == null ? type.getFullyQualifiedName()
					: type.getFullyQualifiedName() + '#'
							+ (paramTypes == null ? memberName : overloads.get(0).signature());
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (!ALL.equals(accessKind) && fields.isEmpty()) {
			return McpToolResult.error(
					"Read and write accesses only exist for fields, and '%s' does not resolve to one. Use accessKind 'all'." //$NON-NLS-1$
							.formatted(resolved));
		}
		List<Overload> searched = searchedFor(overloads, accessKind);

		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);

		// an element search on a binary type only finds references compiled against that one jar, so search the name
		boolean byName = type.isBinary() && memberName == null;
		Found found = find(type, types, memberName, searched, accessKind, scope, monitor, resolved);
		if (found == null) {
			return McpToolResult.error("Could not build a search pattern for '%s'.".formatted(resolved)); //$NON-NLS-1$
		}
		int rawTotal = found.rawTotal();
		List<Hit> hits = found.hits();

		// a field written but never read is dead while text searches see live occurrences; a
		// WRITE_ACCESSES search also reports the initializer, a declaration, hence the separation
		Set<String> declarations = declarationsOf(fields);
		Set<String> reads = null;
		Set<String> writes = null;
		if (ALL.equals(accessKind) && !fields.isEmpty()) {
			reads = locationsOf(search(pattern(fields, IJavaSearchConstants.READ_ACCESSES), scope, monitor, resolved));
			writes = locationsOf(search(pattern(fields, IJavaSearchConstants.WRITE_ACCESSES), scope, monitor, resolved));
		}

		LineIndex lines = new LineIndex();
		JsonArray reported = new JsonArray();
		for (Hit hit : hits.subList(0, Math.min(maxResults, hits.size()))) {
			SearchMatch match = hit.match();
			IResource resource = match.getResource();
			JsonObject entry = new JsonObject();
			JavaModelSupport.describeLocation(match, entry);
			int line = lines.lineOf(resource, match.getOffset());
			entry.put("line", line < 0 ? null : Integer.valueOf(line)); //$NON-NLS-1$
			entry.put("offset", match.getOffset()); //$NON-NLS-1$
			entry.put("length", match.getLength()); //$NON-NLS-1$
			entry.put("signature", hit.signature()); //$NON-NLS-1$
			// a linked file's workspace path does not exist on disk
			entry.put("location", isBinary(match) || resource == null || resource.getLocation() == null ? null //$NON-NLS-1$
					: resource.getLocation().toString());
			entry.put("enclosingElement", //$NON-NLS-1$
					match.getElement() instanceof IJavaElement element ? JavaModelSupport.describe(element) : null);
			entry.put("kind", kindOf(match, accessKind, reads, writes)); //$NON-NLS-1$
			if (declarations.contains(locationOf(match))) {
				entry.put("declaration", Boolean.TRUE); //$NON-NLS-1$
			}
			if (!hit.alsoIn().isEmpty()) {
				JsonArray also = new JsonArray();
				hit.alsoIn().forEach(also::add);
				entry.put("alsoIn", also); //$NON-NLS-1$
			}
			reported.add(entry);
		}

		int binary = 0;
		for (Hit hit : hits) {
			if (isBinary(hit.match())) {
				binary++;
			}
		}
		JsonObject result = new JsonObject().put("resolved", resolved) //$NON-NLS-1$
				.put("resolvedFrom", JavaModelSupport.originOf(type)) //$NON-NLS-1$
				.put("matchedBy", byName ? "name" : "binding") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("accessKind", accessKind) //$NON-NLS-1$
				.put("total", hits.size()) //$NON-NLS-1$
				.put("byOrigin", new JsonObject().put("source", hits.size() - binary).put("binary", binary)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("linkedDuplicates", Integer.valueOf(rawTotal - hits.size())) //$NON-NLS-1$
				.put("truncated", hits.size() > reported.size()); //$NON-NLS-1$
		// said here because a dead-code sweep reads total 0 and moves on
		List<String> injection = searched.stream().flatMap(overload -> overload.members().stream())
				.flatMap(one -> InjectionAnnotations.on(one).stream()).distinct().toList();
		if (!injection.isEmpty()) {
			JsonArray annotations = new JsonArray();
			injection.forEach(annotations::add);
			result.put("injectionAnnotations", annotations) //$NON-NLS-1$
					.put("injectionNote", InjectionAnnotations.warning(injection)); //$NON-NLS-1$
		}
		if (types.size() > 1) {
			JsonArray declaredIn = new JsonArray();
			types.forEach(copy -> declaredIn.add(JavaModelSupport.originOf(copy)));
			result.put("declaredIn", declaredIn) //$NON-NLS-1$
					.put("declaredInNote", //$NON-NLS-1$
							"'%s' is declared %d times in this workspace, once per platform fragment in a repository like SWT. All of them were searched, because a search bound to one copy answers nothing about references that resolve to another." //$NON-NLS-1$
									.formatted(type.getFullyQualifiedName(), Integer.valueOf(types.size())));
		}
		if (!searched.isEmpty()) {
			JsonArray byMember = byMember(searched, hits);
			result.put("byMember", byMember); //$NON-NLS-1$
		}
		if (reads != null) {
			result.put("byKind", new JsonObject().put("read", reads.size()).put("write", writes.size())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (!fields.isEmpty()) {
			int declared = 0;
			for (Hit hit : hits) {
				if (declarations.contains(locationOf(hit.match()))) {
					declared++;
				}
			}
			result.put("byKind", new JsonObject().put(accessKind, hits.size() - declared) //$NON-NLS-1$
					.put("declaration", Integer.valueOf(declared))); //$NON-NLS-1$
		}
		if (type.isBinary() && memberName != null) {
			result.put("caveat", //$NON-NLS-1$
					"'%s' resolved to a compiled type in %s, and a member search binds to that one copy. If several jars supply this qualified name, references compiled against the others are not counted. Search the type without memberName for a name based count." //$NON-NLS-1$
							.formatted(type.getFullyQualifiedName(), JavaModelSupport.originOf(type)));
		} else if (JavaModelSupport.isBuildOutput(type)) {
			result.put("caveat", //$NON-NLS-1$
					"'%s' resolved to a copy inside build output (%s). No project compiles against that, so treat this count with suspicion." //$NON-NLS-1$
							.formatted(type.getFullyQualifiedName(), JavaModelSupport.originOf(type)));
		}
		result.put("matches", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/** The deduplicated hits of a search, and how many there were before folding linked copies. */
	private record Found(List<Hit> hits, int rawTotal) {
	}

	private static List<IMember> fieldsOf(List<Overload> overloads) {
		return overloads.stream().flatMap(overload -> overload.members().stream()).filter(IField.class::isInstance)
				.toList();
	}

	private static List<Overload> searchedFor(List<Overload> overloads, String accessKind) {
		return ALL.equals(accessKind) ? overloads
				: overloads.stream().filter(overload -> overload.members().stream().anyMatch(IField.class::isInstance))
						.toList();
	}

	/**
	 * Runs the searches for a type or for the searched overloads of a member.
	 * Returns {@code null} when no search pattern could be built.
	 */
	private Found find(IType type, List<IType> types, String memberName, List<Overload> searched, String accessKind,
			IJavaSearchScope scope, IProgressMonitor monitor, String label) throws McpToolException {
		List<Hit> hits = new ArrayList<>();
		if (memberName == null) {
			SearchPattern pattern = type.isBinary()
					? SearchPattern.createPattern(type.getFullyQualifiedName(), IJavaSearchConstants.TYPE,
							IJavaSearchConstants.REFERENCES, SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE)
					: pattern(types, IJavaSearchConstants.REFERENCES);
			if (pattern == null) {
				return null;
			}
			for (SearchMatch match : search(pattern, scope, monitor, label)) {
				hits.add(new Hit(match, null));
			}
		} else {
			// one search per overload, so every match knows which overload it belongs to
			boolean any = false;
			for (Overload overload : searched) {
				SearchPattern pattern = pattern(overload.members(), limitTo(accessKind));
				if (pattern == null) {
					continue;
				}
				any = true;
				for (SearchMatch match : search(pattern, scope, monitor, label)) {
					hits.add(new Hit(match, overload.signature()));
				}
			}
			if (!any) {
				return null;
			}
		}
		return new Found(deduplicate(hits), hits.size());
	}

	/** One overload, and the copy of it declared by each project that declares the type. */
	private record Overload(String signature, List<IMember> members) {
	}

	/** A match together with the overload it was found for. */
	private record Hit(SearchMatch match, String signature, Set<String> alsoIn) {

		Hit(SearchMatch match, String signature) {
			this(match, signature, new LinkedHashSet<>());
		}
	}

	/**
	 * Groups the members named {@code memberName} by signature, across every copy of
	 * the declaring type.
	 * The copies of one overload are searched as one OR pattern, which also drops the single element focus.
	 */
	private static List<Overload> overloadsOf(List<IType> types, String memberName, List<String> paramTypes)
			throws ToolInputException, McpToolException {
		Map<String, List<IMember>> bySignature = new LinkedHashMap<>();
		Set<String> available = new LinkedHashSet<>();
		ToolInputException missing = null;
		for (IType type : types) {
			List<IMember> members;
			try {
				members = JavaModelSupport.findMembers(type, memberName);
			} catch (ToolInputException e) {
				missing = e;
				continue;
			}
			members.forEach(member -> available.add(JavaModelSupport.signatureOf(member)));
			for (IMember member : paramTypes == null ? members : select(members, paramTypes)) {
				bySignature.computeIfAbsent(JavaModelSupport.signatureOf(member), key -> new ArrayList<>()).add(member);
			}
		}
		if (bySignature.isEmpty()) {
			if (available.isEmpty()) {
				throw missing == null
						? new ToolInputException("No type declaring '%s' has a member named '%s'.".formatted( //$NON-NLS-1$
								types.get(0).getFullyQualifiedName(), memberName))
						: missing;
			}
			throw new ToolInputException("No overload of '%s#%s' takes (%s). Overloads: %s".formatted( //$NON-NLS-1$
					types.get(0).getFullyQualifiedName(), memberName, String.join(", ", paramTypes), //$NON-NLS-1$
					String.join(", ", available))); //$NON-NLS-1$
		}
		return bySignature.entrySet().stream().map(entry -> new Overload(entry.getKey(), entry.getValue())).toList();
	}

	private static JsonArray byMember(List<Overload> searched, List<Hit> hits) {
		JsonArray byMember = new JsonArray();
		for (Overload overload : searched) {
			int count = 0;
			for (Hit hit : hits) {
				if (overload.signature().equals(hit.signature())) {
					count++;
				}
			}
			byMember.add(new JsonObject().put("signature", overload.signature()) //$NON-NLS-1$
					.put("total", Integer.valueOf(count))); //$NON-NLS-1$
		}
		return byMember;
	}

	private static boolean isBinary(SearchMatch match) {
		return match.getElement() instanceof IJavaElement element
				&& element.getAncestor(IJavaElement.CLASS_FILE) != null;
	}

	/**
	 * Drops matches that are one physical file seen through another project.
	 * A linked source folder (SWT fragments share files) otherwise counts one call site several times.
	 * The folded projects are kept on the surviving match.
	 */
	private static List<Hit> deduplicate(List<Hit> hits) {
		Map<String, Hit> unique = new LinkedHashMap<>();
		for (Hit hit : hits) {
			String key = locationOf(hit.match()) + ':' + hit.match().getLength() + ':'
					+ (hit.signature() == null ? "" : hit.signature()); //$NON-NLS-1$
			Hit kept = unique.putIfAbsent(key, hit);
			if (kept != null && hit.match().getResource() != null
					&& hit.match().getResource().getProject() != null) {
				kept.alsoIn().add(hit.match().getResource().getProject().getName());
			}
		}
		return List.copyOf(unique.values());
	}

	/** The overloads whose parameter list matches, by simple or by qualified type name. */
	private static List<IMember> select(List<IMember> members, List<String> paramTypes) {
		List<IMember> selected = new ArrayList<>();
		for (IMember member : members) {
			if (member instanceof IMethod method && takes(method, paramTypes)) {
				selected.add(member);
			}
		}
		return selected;
	}

	private static boolean takes(IMethod method, List<String> paramTypes) {
		String[] actual = method.getParameterTypes();
		if (actual.length != paramTypes.size()) {
			return false;
		}
		for (int i = 0; i < actual.length; i++) {
			String full = Signature.toString(actual[i]);
			String simple = Signature.getSimpleName(full);
			String wanted = paramTypes.get(i).trim();
			if (!wanted.equals(full) && !wanted.equals(simple) && !Signature.getSimpleName(wanted).equals(simple)) {
				return false;
			}
		}
		return true;
	}

	/** {@code null} when absent, which is not the same as an empty list: that is the no-argument overload. */
	private static List<String> paramTypes(Map<String, Object> arguments) {
		if (!(arguments.get("paramTypes") instanceof List<?> list)) { //$NON-NLS-1$
			return null;
		}
		return list.stream().map(String::valueOf).toList();
	}

	private static int limitTo(String accessKind) {
		return switch (accessKind) {
		case READ -> IJavaSearchConstants.READ_ACCESSES;
		case WRITE -> IJavaSearchConstants.WRITE_ACCESSES;
		default -> IJavaSearchConstants.REFERENCES;
		};
	}

	/** {@code null} for anything that is not a field, since only fields are read and written. */
	private static String kindOf(SearchMatch match, String accessKind, Set<String> reads, Set<String> writes) {
		if (!ALL.equals(accessKind)) {
			return accessKind;
		}
		if (reads == null) {
			return null;
		}
		String location = locationOf(match);
		boolean read = reads.contains(location);
		boolean written = writes.contains(location);
		if (read && written) {
			return "readWrite"; //$NON-NLS-1$
		}
		return read ? READ : written ? WRITE : null;
	}

	/** Where the fields themselves are declared, as match locations. */
	private static Set<String> declarationsOf(List<? extends IMember> fields) {
		Set<String> locations = new LinkedHashSet<>();
		for (IMember field : fields) {
			try {
				ISourceRange range = field.getNameRange();
				IResource resource = field.getResource();
				if (range != null && resource != null) {
					locations.add(locationOf(resource, range.getOffset()));
				}
			} catch (JavaModelException e) {
				// an unreadable range just means the declaration is not recognised
			}
		}
		return locations;
	}

	private static Set<String> locationsOf(List<SearchMatch> matches) {
		return matches.stream().map(FindReferencesTool::locationOf).collect(Collectors.toSet());
	}

	/**
	 * Counts references for many elements in one call.
	 * Counts only, since a sweep needs locations for almost none of hundreds of candidates.
	 */
	private McpToolResult countAll(List<Map<String, Object>> queries, String projectName, IProgressMonitor monitor)
			throws McpToolException {
		List<IJavaProject> projects;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (projects.isEmpty()) {
			return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
		}
		JavaModelSupport.refresh(projectName, monitor);
		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);
		JsonArray results = new JsonArray();
		for (Map<String, Object> query : queries) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			results.add(count(query, projects, scope, monitor));
		}
		return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(results.size())) //$NON-NLS-1$
				.put("countsOnly", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Counts only. Ask the single form about anything whose match locations you need.") //$NON-NLS-1$
				.put("results", results).toString()); //$NON-NLS-1$
	}

	private JsonObject count(Map<String, Object> query, List<IJavaProject> projects, IJavaSearchScope scope,
			IProgressMonitor monitor) throws McpToolException {
		String typeName = String.valueOf(query.get("typeName")); //$NON-NLS-1$
		Object member = query.get("memberName"); //$NON-NLS-1$
		String memberName = member == null || String.valueOf(member).isBlank() ? null
				: String.valueOf(member).trim();
		Object kind = query.get("accessKind"); //$NON-NLS-1$
		String accessKind = kind == null ? ALL : String.valueOf(kind);
		JsonObject entry = new JsonObject().put("typeName", typeName).put("memberName", memberName); //$NON-NLS-1$ //$NON-NLS-2$
		if (!ACCESS_KINDS.contains(accessKind)) {
			return entry.put("error", "Unknown accessKind '%s'.".formatted(accessKind)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<String> paramTypes = paramTypes(query);
		if (paramTypes != null && memberName == null) {
			return entry.put("error", "'paramTypes' picks one overload of 'memberName', so give a memberName too."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<IType> types;
		IType type;
		List<Overload> overloads;
		try {
			types = JavaModelSupport.findTypes(typeName, projects, monitor);
			type = types.get(0);
			overloads = memberName == null ? List.of() : overloadsOf(types, memberName, paramTypes);
		} catch (ToolInputException e) {
			return entry.put("error", e.getMessage()); //$NON-NLS-1$
		}
		List<IMember> fields = fieldsOf(overloads);
		if (!ALL.equals(accessKind) && fields.isEmpty()) {
			return entry.put("error", "Read and write accesses only exist for fields."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<Overload> searched = searchedFor(overloads, accessKind);
		Found found = find(type, types, memberName, searched, accessKind, scope, monitor, typeName);
		if (found == null) {
			return entry.put("error", "Could not build a search pattern."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		int rawTotal = found.rawTotal();
		List<Hit> hits = found.hits();
		Set<String> declarations = declarationsOf(fields);
		int binary = 0;
		int declared = 0;
		for (Hit hit : hits) {
			if (isBinary(hit.match())) {
				binary++;
			}
			if (declarations.contains(locationOf(hit.match()))) {
				declared++;
			}
		}
		entry.put("accessKind", accessKind) //$NON-NLS-1$
				.put("total", Integer.valueOf(hits.size())) //$NON-NLS-1$
				.put("source", Integer.valueOf(hits.size() - binary)) //$NON-NLS-1$
				.put("binary", Integer.valueOf(binary)) //$NON-NLS-1$
				.put("declaration", Integer.valueOf(declared)) //$NON-NLS-1$
				.put("linkedDuplicates", Integer.valueOf(rawTotal - hits.size())); //$NON-NLS-1$
		if (types.size() > 1) {
			entry.put("declaredIn", Integer.valueOf(types.size())); //$NON-NLS-1$
		}
		if (searched.size() > 1) {
			JsonArray byMember = byMember(searched, hits);
			entry.put("byMember", byMember); //$NON-NLS-1$
		}
		return entry;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> queries(Map<String, Object> arguments) {
		List<Map<String, Object>> values = new ArrayList<>();
		if (arguments.get("queries") instanceof List<?> list) { //$NON-NLS-1$
			for (Object value : list) {
				if (value instanceof Map<?, ?> map && map.get("typeName") != null) { //$NON-NLS-1$
					values.add((Map<String, Object>) map);
				}
			}
		}
		return values;
	}

	private static String locationOf(SearchMatch match) {
		return locationOf(match.getResource(), match.getOffset());
	}

	/**
	 * Keyed on the file on disk rather than on the workspace path, so that the same
	 * file reached through several linking projects produces one key.
	 */
	private static String locationOf(IResource resource, int offset) {
		if (resource == null) {
			return "?:" + offset; //$NON-NLS-1$
		}
		IPath path = resource.getLocation();
		return (path == null ? resource.getFullPath() : path) + ":" + offset; //$NON-NLS-1$
	}

	private List<SearchMatch> search(SearchPattern pattern, IJavaSearchScope scope, IProgressMonitor monitor,
			String resolved) throws McpToolException {
		if (pattern == null) {
			return List.of();
		}
		List<SearchMatch> matches = new ArrayList<>();
		SearchRequestor requestor = new SearchRequestor() {
			@Override
			public void acceptSearchMatch(SearchMatch match) {
				matches.add(match);
			}
		};
		try {
			new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope, requestor, monitor);
		} catch (CoreException e) {
			throw new McpToolException("The JDT search for references to %s failed".formatted(resolved), e); //$NON-NLS-1$
		}
		return matches;
	}

	private static SearchPattern pattern(List<? extends IMember> members, int limitTo) {
		SearchPattern combined = null;
		for (IMember member : members) {
			SearchPattern pattern = SearchPattern.createPattern(member, limitTo);
			if (pattern == null) {
				continue;
			}
			combined = combined == null ? pattern : SearchPattern.createOrPattern(combined, pattern);
		}
		return combined;
	}
}
