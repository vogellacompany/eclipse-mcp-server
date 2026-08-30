package com.vogella.eclipse.mcp.pde.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.TargetBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds files by name across the workspace, the target platform and the running
 * installation.
 */
public final class FindResourcesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_find_resources"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds files by NAME across three places that no other tool here covers together: the workspace, the bundles of the active target platform, and the bundles of the running installation. Changes nothing. eclipse_search_text searches the CONTENT of workspace files, so an icon inside a jarred bundle is invisible to it, and that is the gap this closes: 'does an SVG of this name already exist anywhere in Eclipse' is answerable only by looking inside jars. Each hit says which of the three it came from, the bundle and version that holds it, the path inside that bundle, and a location the bytes can be read from; copyTo extracts the hits to a directory, which is what makes a found icon usable rather than only known about. FOR AN INVENTORY, ask several names at once with namePatterns and cut the answer down with compact or countOnly, rather than one call per name; and use scope 'installation', because 'all' returns the same picture three or four times over when the repositories are also in the workspace. A NAME MATCH IS NOT A MATCH IN MEANING: an icon called remove upstream may be a red cross where yours is a minus, so look at what you found before replacing anything with it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "namePattern":  {"type":"string","description":"Glob over the FILE NAME only, not the path: '*question*', 'refresh.svg', 'overlay-*'. Case insensitive."},
				    "namePatterns": {"type":"array","items":{"type":"string"},"description":"Several such globs in one call, which is the cheap way to ask 'which of these 180 names exist'. Each hit says which pattern matched, and the answer counts them separately."},
				    "extensions":   {"type":"array","items":{"type":"string"},"description":"Only these file extensions, without the dot, e.g. ['svg','png','gif']."},
				    "scope":        {"type":"string","enum":["workspace","target","installation","all"],"default":"all","description":"Where to look. 'target' needs an active target platform, 'installation' looks inside the bundles this IDE is running, jars included, and is the one to use for an inventory."},
				    "bundleFilter": {"type":"string","description":"Only bundles whose symbolic name contains this text. Narrows a search over a few thousand icons to one component."},
				    "compact":      {"type":"boolean","default":false,"description":"Report only the bundle and the path per hit, about a fifth of the bytes. Enough to see what exists; ask again without it for the one hit you then care about."},
				    "countOnly":    {"type":"boolean","default":false,"description":"Report only how many hits each pattern has, no hits at all. The cheapest answer to 'does a file of this name exist'."},
				    "copyTo":       {"type":"string","description":"Absolute directory to copy each hit into. The bundle and the whole path inside it make up the file name, so four files called help.svg in one bundle arrive as four files. This is how the bytes leave a jar; without it the hit is only a location."},
				    "dedupe":       {"type":"boolean","default":false,"description":"Report each distinct FILE CONTENT once. The same icon commonly exists four times over, in a repository clone, in the aggregator, as a workspace project and in the installation, so a plain search returns dozens of hits for a handful of pictures. Every hit carries a contentHash either way, so a caller can group them without this."},
				    "includeDerived":{"type":"boolean","default":false,"description":"Also search build output: bin and target folders and anything the workspace marks as derived. Off by default, because that is a second copy of every icon."},
				    "maxResults":   {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"An SDK holds thousands of icons, so this is a small number on purpose; narrow the pattern rather than raising it."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		List<String> globs = new ArrayList<>(strings(arguments, "namePatterns")); //$NON-NLS-1$
		if (args.getString("namePattern") != null) { //$NON-NLS-1$
			globs.add(0, args.getString("namePattern")); //$NON-NLS-1$
		}
		globs.removeIf(String::isEmpty);
		if (globs.isEmpty()) {
			return McpToolResult
					.error("Give 'namePattern', for instance '*question*', or 'namePatterns' with several of them."); //$NON-NLS-1$
		}

		Search search = new Search(globs, strings(arguments, "extensions"), args.getString("bundleFilter"), //$NON-NLS-1$ //$NON-NLS-2$
				args.getBoolean("includeDerived", false), args.getBoolean("dedupe", false), //$NON-NLS-1$ //$NON-NLS-2$
				args.getBoolean("compact", false), args.getBoolean("countOnly", false), //$NON-NLS-1$ //$NON-NLS-2$
				args.getInt("maxResults", 100, 1, 1000)); //$NON-NLS-1$
		if (args.getString("copyTo") != null) { //$NON-NLS-1$
			search.copyTo = Path.of(args.getString("copyTo")); //$NON-NLS-1$
			try {
				Files.createDirectories(search.copyTo);
			} catch (IOException e) {
				return McpToolResult.error("Could not create '%s': %s".formatted(search.copyTo, e.getMessage())); //$NON-NLS-1$
			}
		}

		String scope = args.getString("scope", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		if (scope.equals("workspace") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchWorkspace(search);
		}
		if (scope.equals("installation") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchInstallation(search);
		}
		if (scope.equals("target") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchTarget(search);
		}

		JsonObject result = new JsonObject().put("total", Integer.valueOf(search.total)) //$NON-NLS-1$
				.put("scope", scope); //$NON-NLS-1$
		if (globs.size() > 1 || search.countOnly) {
			JsonObject counts = new JsonObject();
			for (String glob : globs) {
				counts.put(glob, Integer.valueOf(search.counts.get(glob)[0]));
			}
			result.put("counts", counts); //$NON-NLS-1$
		}
		if (!search.countOnly) {
			result.put("hits", search.hits) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(search.total > search.hits.size())); //$NON-NLS-1$
		}
		if (search.copyTo != null) {
			result.put("copiedTo", search.copyTo.toString()); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("note", note(search)).toString()); //$NON-NLS-1$
	}

	private static String note(Search search) {
		if (search.total == 0) {
			return "Nothing matched. The pattern is over the file name alone, so a path fragment such as icons/full does not belong in it, and 'target' finds nothing when no target platform is active."; //$NON-NLS-1$
		}
		if (search.countOnly) {
			return "Counts only. Ask again for the patterns that are not zero, with compact for a list of paths."; //$NON-NLS-1$
		}
		return "A name match says nothing about the picture. Copy the hits with copyTo and look at them before treating one as a replacement for another."; //$NON-NLS-1$
	}

	private static void searchWorkspace(Search search) {
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept((IResourceProxy proxy) -> {
				if (proxy.getType() != IResource.FILE) {
					// build output holds a copy of every icon, so a search over it
					// reports each one twice for no gain
					return search.derived || !isBuildOutput(proxy.requestFullPath().toString());
				}
				String glob = search.match(proxy.getName());
				if (glob == null || (proxy.isDerived() && !search.derived)) {
					return false;
				}
				// before counting: filtering after the count is what made the filter
				// look as though it changed only the total and not the hits
				IPath full = proxy.requestFullPath();
				if (search.bundleFilter != null
						&& (full.segmentCount() == 0 || !full.segment(0).contains(search.bundleFilter))) {
					return false;
				}
				IResource resource = proxy.requestResource();
				String path = resource.getProjectRelativePath().toString();
				String bundle = resource.getProject().getName();
				if (!(resource instanceof IFile file)) {
					return false;
				}
				JsonObject hit = new JsonObject();
				if (!search.compact) {
					URI location = resource.getLocationURI();
					hit.put("scope", "workspace") //$NON-NLS-1$ //$NON-NLS-2$
							.put("workspacePath", resource.getFullPath().toString()) //$NON-NLS-1$
							.put("location", location == null ? null : location.getPath()); //$NON-NLS-1$
				}
				search.take(glob, bundle, path, hit, () -> file.getContents(true));
				return false;
			}, IResource.NONE);
		} catch (CoreException e) {
			// a workspace that cannot be walked still leaves the other scopes usable
		}
	}

	private static void searchInstallation(Search search) {
		Bundle self = FrameworkUtil.getBundle(FindResourcesTool.class);
		if (self == null || self.getBundleContext() == null) {
			return;
		}
		for (Bundle bundle : self.getBundleContext().getBundles()) {
			if (search.bundleFilter != null && !bundle.getSymbolicName().contains(search.bundleFilter)) {
				continue;
			}
			// findEntries reads the bundle whether it is a directory or a jar, which is
			// the whole reason this reaches an icon a content search cannot see
			var entries = bundle.findEntries("/", "*", true); //$NON-NLS-1$ //$NON-NLS-2$
			while (entries != null && entries.hasMoreElements()) {
				URL url = entries.nextElement();
				String path = url.getPath();
				String fileName = path.substring(path.lastIndexOf('/') + 1);
				String glob = search.match(fileName);
				if (glob == null || (!search.derived && isBuildOutput(path))) {
					continue;
				}
				JsonObject hit = new JsonObject();
				if (!search.compact) {
					hit.put("scope", "installation") //$NON-NLS-1$ //$NON-NLS-2$
							.put("version", String.valueOf(bundle.getVersion())) //$NON-NLS-1$
							.put("location", url.toExternalForm()); //$NON-NLS-1$
				}
				search.take(glob, bundle.getSymbolicName(), path.startsWith("/") ? path.substring(1) : path, hit, //$NON-NLS-1$
						url::openStream);
			}
		}
	}

	private static void searchTarget(Search search) {
		TargetPlatforms.with(service -> {
			try {
				ITargetDefinition definition = service.getWorkspaceTargetDefinition();
				if (definition == null || definition.getBundles() == null) {
					return McpToolResult.of("{}"); //$NON-NLS-1$
				}
				for (TargetBundle bundle : definition.getBundles()) {
					// PDE returns the bundle info from API while the type itself is
					// internal to frameworkadmin, so the compiler refuses to name it and
					// the three values have to be asked for reflectively
					Object info = bundle.getBundleInfo();
					String symbolicName = string(info, "getSymbolicName"); //$NON-NLS-1$
					String version = string(info, "getVersion"); //$NON-NLS-1$
					String location = string(info, "getLocation"); //$NON-NLS-1$
					if (location == null || (search.bundleFilter != null
							&& (symbolicName == null || !symbolicName.contains(search.bundleFilter)))) {
						continue;
					}
					// getLocation is a file: URL, and reading its path directly hands
					// back "/C:/..." on Windows and leaves %20 in an encoded one
					Path resolved = FileLocations.pathOf(location);
					File file = resolved == null ? new File(location) : resolved.toFile();
					if (file.isFile()) {
						searchJar(file, symbolicName, version, search);
					}
				}
			} catch (CoreException | RuntimeException e) {
				// no resolved target platform, which the note already explains
			}
			return McpToolResult.of("{}"); //$NON-NLS-1$
		});
	}

	private static void searchJar(File jar, String symbolicName, String version, Search search) {
		try (ZipFile zip = new ZipFile(jar)) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				String path = entry.getName();
				String glob = search.match(path.substring(path.lastIndexOf('/') + 1));
				if (glob == null) {
					continue;
				}
				JsonObject hit = new JsonObject();
				if (!search.compact) {
					hit.put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
							.put("version", version) //$NON-NLS-1$
							.put("bytes", Long.valueOf(entry.getSize())) //$NON-NLS-1$
							.put("location", "jar:%s!/%s".formatted(jar.toURI(), path)); //$NON-NLS-1$ //$NON-NLS-2$
				}
				search.take(glob, symbolicName, path, hit, () -> zip.getInputStream(entry));
			}
		} catch (IOException e) {
			// an unreadable jar is not a reason to abandon the rest of the target
		}
	}

	/** One string property of an object whose type cannot be named here. */
	private static String string(Object target, String method) {
		if (target == null) {
			return null;
		}
		try {
			Object value = target.getClass().getMethod(method).invoke(target);
			return value == null ? null : String.valueOf(value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Build output, which holds a second copy of every icon of a project. */
	private static boolean isBuildOutput(String path) {
		return path.contains("/bin/") || path.contains("/target/classes/"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Opens the bytes of a hit, wherever they live. */
	private interface Bytes {
		InputStream open() throws IOException, CoreException;
	}

	/** The request, and what has been found for it so far. */
	private static final class Search {
		private final List<String> globs;
		private final List<Pattern> patterns = new ArrayList<>();
		private final List<String> extensions;
		final String bundleFilter;
		final boolean derived;
		final boolean dedupe;
		final boolean compact;
		final boolean countOnly;
		private final int maxResults;
		Path copyTo;
		final JsonArray hits = new JsonArray();
		final Map<String, int[]> counts = new LinkedHashMap<>();
		private final Set<String> seen = new HashSet<>();
		int total;

		Search(List<String> globs, List<String> extensions, String bundleFilter, boolean derived, boolean dedupe,
				boolean compact, boolean countOnly, int maxResults) {
			this.globs = globs;
			this.extensions = extensions;
			this.bundleFilter = bundleFilter;
			this.derived = derived;
			this.dedupe = dedupe;
			this.compact = compact;
			this.countOnly = countOnly;
			this.maxResults = maxResults;
			for (String glob : globs) {
				patterns.add(Globs.compile(glob));
				counts.put(glob, new int[1]);
			}
		}

		/** The pattern this file name matches, or null. */
		String match(String fileName) {
			if (fileName.isEmpty()) {
				return null;
			}
			if (!extensions.isEmpty()) {
				int dot = fileName.lastIndexOf('.');
				String extension = dot < 0 ? "" : fileName.substring(dot + 1); //$NON-NLS-1$
				if (!extensions.stream().anyMatch(extension::equalsIgnoreCase)) {
					return null;
				}
			}
			for (int i = 0; i < patterns.size(); i++) {
				if (patterns.get(i).matcher(fileName).matches()) {
					return globs.get(i);
				}
			}
			return null;
		}

		/** Counts a match, and records it as a hit unless the answer is already full. */
		void take(String glob, String bundle, String path, JsonObject hit, Bytes bytes) {
			boolean reporting = !countOnly && hits.size() < maxResults;
			String hash = dedupe || (reporting && !compact) ? hash(bytes) : null;
			if (dedupe && hash != null && !seen.add(hash)) {
				return;
			}
			total++;
			counts.get(glob)[0]++;
			if (!reporting) {
				return;
			}
			hit.put("bundle", bundle).put("path", path); //$NON-NLS-1$ //$NON-NLS-2$
			if (globs.size() > 1) {
				hit.put("matched", glob); //$NON-NLS-1$
			}
			if (hash != null && !compact) {
				hit.put("contentHash", hash); //$NON-NLS-1$
			}
			copy(bundle, path, bytes, hit);
			hits.add(hit);
		}

		private void copy(String bundle, String path, Bytes bytes, JsonObject hit) {
			if (copyTo == null) {
				return;
			}
			// the whole path, not the file name alone: one bundle holds several
			// files of the same name in different folders, and naming a copy after
			// the bundle only made them overwrite each other in silence
			Path target = copyTo.resolve((bundle + "_" + path).replaceAll("[^A-Za-z0-9._-]+", "_")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			try (InputStream in = bytes.open()) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				hit.put("copiedTo", target.toString()); //$NON-NLS-1$
			} catch (IOException | CoreException | RuntimeException e) {
				hit.put("copyFailed", String.valueOf(e.getMessage())); //$NON-NLS-1$
			}
		}

		/** A digest of the content, which is what tells four copies of one icon from four icons. */
		private static String hash(Bytes bytes) {
			try (InputStream in = bytes.open()) {
				MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
				byte[] buffer = new byte[8192];
				for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
					digest.update(buffer, 0, read);
				}
				byte[] result = digest.digest();
				StringBuilder text = new StringBuilder();
				for (int i = 0; i < 8 && i < result.length; i++) {
					text.append("%02x".formatted(Byte.valueOf(result[i]))); //$NON-NLS-1$
				}
				return text.toString();
			} catch (IOException | CoreException | RuntimeException | NoSuchAlgorithmException e) {
				return null;
			}
		}
	}

	private static List<String> strings(Map<String, Object> arguments, String key) {
		Object raw = arguments == null ? null : arguments.get(key);
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object value : list) {
			if (value != null) {
				values.add(String.valueOf(value).strip());
			}
		}
		return values;
	}
}
