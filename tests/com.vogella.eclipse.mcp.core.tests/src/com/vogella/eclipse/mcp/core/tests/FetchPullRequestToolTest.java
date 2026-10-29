package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Fetching a pull request into a local branch, against a repository on disk.
 * <p>
 * GitHub is stood in for twice: the upstream repository advertises the pull
 * request as {@code refs/pull/7/head}, which is all the fetch needs, and a
 * local HTTP server answers the one API call that names the branch the pull
 * request was opened from. The property the tool honours for that URL exists
 * for this test, since a file URL has no host to derive an API from.
 */
class FetchPullRequestToolTest {

	private static final String NAME = "eclipse_fetch_pull_request";
	private static final String API_PROPERTY = "com.vogella.eclipse.mcp.pullRequestApi";
	private static final String HEAD_BRANCH = "feature/gap-colour";

	private Path root;
	private Path upstream;
	private Path local;
	private HttpServer api;
	private String headSha;

	@BeforeEach
	void createRepositories() throws Exception {
		root = Files.createTempDirectory("mcp-git-pr");
		upstream = root.resolve("upstream");
		local = root.resolve("local");
		try (Git git = Git.init().setDirectory(upstream.toFile()).setInitialBranch("main").call()) {
			commit(git, "tracked.txt", "first\n", "first");
			git.checkout().setCreateBranch(true).setName(HEAD_BRANCH).call();
			headSha = commit(git, "tracked.txt", "from the pull request\n", "pull request").name();
			git.checkout().setName("main").call();
			advertise(git.getRepository(), ObjectId.fromString(headSha));
		}
		try (Git git = Git.cloneRepository().setURI(upstream.toUri().toString()).setDirectory(local.toFile())
				.setBranch("main").call()) {
			assertEquals("main", git.getRepository().getBranch());
		}
		api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		api.createContext("/repos/acme/widgets/pulls/7", exchange -> {
			byte[] body = """
					{"title":"Adapt the ruler gap","state":"open","draft":false,
					 "user":{"login":"dev"},
					 "head":{"ref":"%s","sha":"%s","repo":{"full_name":"dev/widgets"}},
					 "base":{"ref":"main"},
					 "html_url":"https://github.com/acme/widgets/pull/7"}
					""".formatted(HEAD_BRANCH, headSha).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		api.start();
		System.setProperty(API_PROPERTY, "http://127.0.0.1:" + api.getAddress().getPort() + "/repos/acme/widgets");
	}

	@AfterEach
	void tearDown() throws Exception {
		System.clearProperty(API_PROPERTY);
		if (api != null) {
			api.stop(0);
		}
		release(root);
		delete(root);
	}

	/**
	 * Closes the repositories the tool opened through EGit's cache, which keeps them
	 * open on purpose; on Windows an open pack file cannot be deleted.
	 */
	private static void release(Path directory) {
		for (Repository repository : RepositoryCache.INSTANCE.getAllRepositories()) {
			if (repository.getDirectory().toPath().startsWith(directory)) {
				repository.close();
			}
		}
		RepositoryCache.INSTANCE.clear();
	}

	/**
	 * Deletes the temporary clone, retrying whatever is left over.
	 * <p>
	 * One walk is not enough: jgit's own background work writes a lock or a log
	 * file into a directory the walk has already listed, and that directory then
	 * fails to delete while everything around it succeeds, which fails the test
	 * that happened to run last rather than anything that is wrong.
	 */
	private static void delete(Path directory) throws IOException {
		for (int attempt = 0; directory != null && attempt < 5 && Files.exists(directory); attempt++) {
			try (var walk = Files.walk(directory)) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					try {
						// jgit writes objects read-only, which Windows refuses to delete
						path.toFile().setWritable(true);
						Files.deleteIfExists(path);
					} catch (DirectoryNotEmptyException e) {
						// something appeared after the walk listed it, so the next attempt takes it
					}
				}
			}
		}
	}

	@Test
	void theDryRunNamesTheBranchAndChecksTheRemoteWithoutFetching() throws Exception {
		Map<String, Object> answer = call(Map.of("number", Integer.valueOf(7)));

		assertEquals(Boolean.TRUE, answer.get("dryRun"), "got " + answer);
		assertEquals(HEAD_BRANCH, answer.get("branch"), "got " + answer);
		assertEquals("pullRequest", answer.get("branchSource"), "got " + answer);
		assertEquals(Boolean.TRUE, answer.get("advertised"), "got " + answer);
		assertEquals(headSha, answer.get("headSha"), "got " + answer);
		assertEquals("create", answer.get("branchAction"), "got " + answer);
		assertNull(answer.get("apiError"), "got " + answer);
		try (Git git = Git.open(local.toFile())) {
			assertNull(git.getRepository().exactRef("refs/heads/" + HEAD_BRANCH), "a dry run must not create the branch");
			assertEquals("main", git.getRepository().getBranch());
		}
	}

	@Test
	void fetchesIntoABranchNamedAfterThePullRequestAndChecksItOut() throws Exception {
		Map<String, Object> answer = call(Map.of("number", Integer.valueOf(7), "dryRun", Boolean.FALSE));

		assertEquals("created", answer.get("branchAction"), "got " + answer);
		assertEquals(Boolean.TRUE, answer.get("checkedOut"), "got " + answer);
		assertEquals(headSha, answer.get("headSha"), "got " + answer);
		@SuppressWarnings("unchecked")
		Map<String, Object> pullRequest = (Map<String, Object>) answer.get("pullRequest");
		assertNotNull(pullRequest, "got " + answer);
		assertEquals("Adapt the ruler gap", pullRequest.get("title"));
		assertEquals("main", pullRequest.get("baseBranch"));
		try (Git git = Git.open(local.toFile())) {
			assertEquals(HEAD_BRANCH, git.getRepository().getBranch());
			assertEquals(headSha, git.getRepository().resolve("HEAD").name());
		}
		assertEquals("from the pull request\n", workingCopy("tracked.txt"),
				"the working tree has to hold the pull request's version");
	}

	@Test
	void fallsBackToTheNumberWhenTheApiCannotBeAsked() throws Exception {
		api.stop(0);

		Map<String, Object> answer = call(
				Map.of("number", Integer.valueOf(7), "dryRun", Boolean.FALSE, "checkout", Boolean.FALSE));

		assertEquals("pr-7", answer.get("branch"), "got " + answer);
		assertEquals("fallback", answer.get("branchSource"), "got " + answer);
		assertNotNull(answer.get("apiError"), "the answer has to say why the name is the fallback, got " + answer);
		assertEquals(Boolean.FALSE, answer.get("checkedOut"), "got " + answer);
		try (Git git = Git.open(local.toFile())) {
			assertEquals(headSha, git.getRepository().exactRef("refs/heads/pr-7").getObjectId().name());
			assertEquals("main", git.getRepository().getBranch(), "checkout false has to leave the branch alone");
		}
	}

	@Test
	void aPullRequestTheRemoteDoesNotAdvertiseIsRefusedBeforeAndAfterTheDryRun() throws Exception {
		Map<String, Object> dry = call(Map.of("number", Integer.valueOf(99)));
		assertEquals(Boolean.FALSE, dry.get("advertised"), "got " + dry);

		McpToolResult real = TestFixture.call(NAME,
				Map.of("directory", local.toString(), "number", Integer.valueOf(99), "dryRun", Boolean.FALSE));
		assertTrue(real.isError(), "got " + real.text());
		assertTrue(real.text().contains("refs/pull/99/head"), "got " + real.text());
	}

	@Test
	void aSecondFetchFastForwardsTheCheckedOutBranch() throws Exception {
		call(Map.of("number", Integer.valueOf(7), "dryRun", Boolean.FALSE));
		String moved;
		try (Git git = Git.open(upstream.toFile())) {
			git.checkout().setName(HEAD_BRANCH).call();
			moved = commit(git, "tracked.txt", "revised\n", "revised").name();
			git.checkout().setName("main").call();
			advertise(git.getRepository(), ObjectId.fromString(moved));
		}

		Map<String, Object> answer = call(Map.of("number", Integer.valueOf(7), "dryRun", Boolean.FALSE));

		assertEquals("fastForwarded", answer.get("branchAction"), "got " + answer);
		assertEquals(moved, answer.get("headSha"), "got " + answer);
		try (Git git = Git.open(local.toFile())) {
			assertEquals(HEAD_BRANCH, git.getRepository().getBranch());
			assertEquals(moved, git.getRepository().resolve("HEAD").name());
		}
		assertEquals("revised\n", workingCopy("tracked.txt"));
	}

	/** The checked out file with LF line endings, whatever core.autocrlf the user's git config sets. */
	private String workingCopy(String file) throws IOException {
		return Files.readString(local.resolve(file)).replace("\r\n", "\n");
	}

	@Test
	void aBranchWithCommitsOfItsOwnIsLeftAlone() throws Exception {
		call(Map.of("number", Integer.valueOf(7), "dryRun", Boolean.FALSE, "checkout", Boolean.FALSE));
		try (Git git = Git.open(local.toFile())) {
			git.checkout().setName(HEAD_BRANCH).call();
			commit(git, "local.txt", "mine\n", "local work");
			git.checkout().setName("main").call();
		}
		try (Git git = Git.open(upstream.toFile())) {
			git.checkout().setName(HEAD_BRANCH).call();
			advertise(git.getRepository(), commit(git, "tracked.txt", "revised\n", "revised"));
			git.checkout().setName("main").call();
		}

		McpToolResult result = TestFixture.call(NAME,
				Map.of("directory", local.toString(), "number", Integer.valueOf(7), "dryRun", Boolean.FALSE));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("'branch'"), "the refusal has to point at the way out, got " + result.text());
		try (Git git = Git.open(local.toFile())) {
			assertFalse(git.getRepository().exactRef("refs/heads/" + HEAD_BRANCH).getObjectId().name().equals(headSha),
					"the local commit has to survive");
			assertEquals("main", git.getRepository().getBranch());
		}
	}

	@Test
	void aMissingNumberAndAMissingRemoteAreRefusedWithAHint() throws Exception {
		McpToolResult noNumber = TestFixture.call(NAME, Map.of("directory", local.toString()));
		assertTrue(noNumber.isError() && noNumber.text().contains("number"), "got " + noNumber.text());

		McpToolResult noRemote = TestFixture.call(NAME,
				Map.of("directory", local.toString(), "number", Integer.valueOf(7), "remote", "upstream"));
		assertTrue(noRemote.isError() && noRemote.text().contains("origin"),
				"the refusal has to list the remotes that exist, got " + noRemote.text());
	}

	private Map<String, Object> call(Map<String, Object> arguments) throws Exception {
		Map<String, Object> withDirectory = new java.util.HashMap<>(arguments);
		withDirectory.put("directory", local.toString());
		return TestFixture.callAndParse(NAME, withDirectory);
	}

	private static RevCommit commit(Git git, String file, String content, String message) throws Exception {
		Files.writeString(git.getRepository().getWorkTree().toPath().resolve(file), content);
		git.add().addFilepattern(file).call();
		return git.commit().setMessage(message).setAuthor("Test", "test@example.com")
				.setCommitter("Test", "test@example.com").setSign(Boolean.FALSE).call();
	}

	/** What GitHub does for every pull request: advertise its head under refs/pull. */
	private static void advertise(Repository repository, ObjectId commit) throws Exception {
		RefUpdate update = repository.updateRef("refs/pull/7/head");
		update.setNewObjectId(commit);
		update.setForceUpdate(true);
		update.update();
	}
}
