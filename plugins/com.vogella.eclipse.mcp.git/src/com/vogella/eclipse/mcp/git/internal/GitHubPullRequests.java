package com.vogella.eclipse.mcp.git.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.eclipse.jgit.transport.URIish;

import com.vogella.eclipse.mcp.core.json.Json;

/**
 * Asks the GitHub REST API what it knows about a pull request, best effort.
 * <p>
 * The fetch needs none of this; only the API knows the pull request's source branch name,
 * which names the local branch.
 */
final class GitHubPullRequests {

	/** Full URL of the repository's API resource, for tests; the tool appends {@code /pulls/N}. */
	static final String API_PROPERTY = "com.vogella.eclipse.mcp.pullRequestApi"; //$NON-NLS-1$

	private static final String[] TOKEN_VARIABLES = { "GH_TOKEN", "GITHUB_TOKEN" }; //$NON-NLS-1$ //$NON-NLS-2$

	private static final String[] ENTERPRISE_TOKEN_VARIABLES = { "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN" }; //$NON-NLS-1$ //$NON-NLS-2$

	record PullRequest(String title, String state, boolean draft, String author, String headRepository,
			String headBranch, String headSha, String baseBranch, String url) {
	}

	record Answer(PullRequest pullRequest, String error, boolean authenticated) {
	}

	private GitHubPullRequests() {
	}

	/** The API URL for the pull request, or {@code null} when the remote is not a GitHub repository. */
	static String apiUrl(URIish remote, int number) {
		String override = System.getProperty(API_PROPERTY);
		if (override != null && !override.isBlank()) {
			return override + "/pulls/" + number; //$NON-NLS-1$
		}
		String host = remote.getHost();
		String path = remote.getPath();
		if (host == null || host.isBlank() || path == null) {
			return null;
		}
		path = path.strip();
		while (path.startsWith("/")) { //$NON-NLS-1$
			path = path.substring(1);
		}
		while (path.endsWith("/")) { //$NON-NLS-1$
			path = path.substring(0, path.length() - 1);
		}
		if (path.endsWith(".git")) { //$NON-NLS-1$
			path = path.substring(0, path.length() - 4);
		}
		String[] segments = path.split("/"); //$NON-NLS-1$
		if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
			return null;
		}
		String base = "github.com".equalsIgnoreCase(host) ? "https://api.github.com" : "https://" + host + "/api/v3"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		return base + "/repos/" + segments[0] + "/" + segments[1] + "/pulls/" + number; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	static Answer read(URIish remote, int number, int timeoutSeconds) {
		String url = apiUrl(remote, number);
		if (url == null) {
			return new Answer(null, "The remote URL '%s' is not of the form host/owner/repository, so there is no GitHub API to ask." //$NON-NLS-1$
					.formatted(remote), false);
		}
		String token = token(url, remote.getHost());
		HttpRequest.Builder request;
		try {
			request = HttpRequest.newBuilder(URI.create(url));
		} catch (IllegalArgumentException e) {
			return new Answer(null, "The API URL '%s' is not a valid URI: %s".formatted(url, e.getMessage()), false); //$NON-NLS-1$
		}
		request = request.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Accept", "application/vnd.github+json") //$NON-NLS-1$ //$NON-NLS-2$
				.header("X-GitHub-Api-Version", "2022-11-28") //$NON-NLS-1$ //$NON-NLS-2$
				.header("User-Agent", "eclipse-mcp-server") //$NON-NLS-1$ //$NON-NLS-2$
				.GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
				.followRedirects(HttpClient.Redirect.NORMAL).build()) {
			HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return new Answer(null, describe(url, response.statusCode(), token != null), token != null);
			}
			return new Answer(parse(response.body()), null, token != null);
		} catch (IOException | RuntimeException e) {
			return new Answer(null, "Could not read %s: %s".formatted(url, e.getMessage()), token != null); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Answer(null, "Interrupted while reading " + url, token != null); //$NON-NLS-1$
		}
	}

	private static String describe(String url, int status, boolean authenticated) {
		String hint = switch (status) {
		case 404 -> authenticated ? "no such pull request, or the token cannot see the repository" //$NON-NLS-1$
				: "no such pull request, or a private repository and no GH_TOKEN or GITHUB_TOKEN is set"; //$NON-NLS-1$
		case 401 -> "the token in GH_TOKEN or GITHUB_TOKEN was rejected"; //$NON-NLS-1$
		case 403, 429 -> authenticated ? "forbidden or rate limited" : "rate limited; set GH_TOKEN or GITHUB_TOKEN"; //$NON-NLS-1$ //$NON-NLS-2$
		default -> "unexpected status"; //$NON-NLS-1$
		};
		return "%s answered %d: %s.".formatted(url, Integer.valueOf(status), hint); //$NON-NLS-1$
	}

	@SuppressWarnings("unchecked")
	private static PullRequest parse(String body) {
		Map<String, Object> pr = (Map<String, Object>) Json.parse(body);
		Map<String, Object> head = map(pr.get("head")); //$NON-NLS-1$
		Map<String, Object> headRepo = map(head.get("repo")); //$NON-NLS-1$
		return new PullRequest(string(pr.get("title")), string(pr.get("state")), //$NON-NLS-1$ //$NON-NLS-2$
				Boolean.TRUE.equals(pr.get("draft")), string(map(pr.get("user")).get("login")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				string(headRepo.get("full_name")), string(head.get("ref")), string(head.get("sha")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				string(map(pr.get("base")).get("ref")), string(pr.get("html_url"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	private static String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * The token for this host: GH_TOKEN and GITHUB_TOKEN only go to api.github.com, and an
	 * enterprise host gets its own variables and only when it is the GH_HOST the gh CLI uses.
	 */
	static String token(String url, String host) {
		if (System.getProperty(API_PROPERTY) != null && !System.getProperty(API_PROPERTY).isBlank()) {
			return null;
		}
		if (url.startsWith("https://api.github.com/")) { //$NON-NLS-1$
			return firstSet(TOKEN_VARIABLES);
		}
		String ghHost = System.getenv("GH_HOST"); //$NON-NLS-1$
		return host != null && host.equalsIgnoreCase(ghHost) ? firstSet(ENTERPRISE_TOKEN_VARIABLES) : null;
	}

	private static String firstSet(String[] variables) {
		for (String variable : variables) {
			String value = System.getenv(variable);
			if (value != null && !value.isBlank()) {
				return value.strip();
			}
		}
		return null;
	}
}
