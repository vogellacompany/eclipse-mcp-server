package com.vogella.eclipse.mcp.git.internal;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.op.CreateLocalBranchOperation;
import org.eclipse.egit.core.op.FetchOperation;
import org.eclipse.egit.core.op.MergeOperation;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Fetches a GitHub pull request into a local branch through EGit, the way
 * "Fetch GitHub Pull Request" in the Git Repositories view does.
 */
public final class FetchPullRequestTool implements IMcpTool {

	private static final int API_TIMEOUT_SECONDS = 10;

	@Override
	public String getName() {
		return "eclipse_fetch_pull_request"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Fetches a GitHub pull request into a local branch and checks it out, which is what 'Fetch GitHub Pull Request' in the Git Repositories view does. CHANGES THE REPOSITORY AND THE WORKING TREE: it downloads the pull request's commits, creates or fast-forwards a local branch, and switches to it unless checkout is false. Runs as a dry run unless dryRun is set to false. GitHub advertises every pull request as the git ref refs/pull/N/head, so the fetch is a plain git fetch through EGit with the credentials the user already configured, and works without any token. The local branch is NAMED AFTER THE BRANCH THE PULL REQUEST WAS OPENED FROM, which the GitHub REST API is asked for; when the API cannot be reached, or the repository is private and neither GH_TOKEN nor GITHUB_TOKEN is set, the branch falls back to pr-N and the answer says why. Pass branch to choose the name yourself. A local branch of that name that already exists is reused when it points at the same commit, fast-forwarded when the pull request moved on, and refused when it has commits of its own. The switch goes through EGit, so the affected projects refresh as part of it. Needs EGit; without it the tools say so rather than guessing. Nothing is merged and nothing is pushed."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "number":    {"type":"integer","minimum":1,"description":"The pull request number."},
				    "project":   {"type":"string","description":"Project whose repository to fetch into, resolved the way the Git Repositories view resolves it."},
				    "directory": {"type":"string","description":"Working tree or .git directory, for a repository that is not in the workspace. Ignored when project is given."},
				    "remote":    {"type":"string","default":"origin","description":"The remote that points at the GitHub repository the pull request was opened against."},
				    "branch":    {"type":"string","description":"Name for the local branch. Defaults to the branch the pull request was opened from, or pr-N when that cannot be determined."},
				    "checkout":  {"type":"boolean","default":true,"description":"Switch the working tree to the branch after fetching."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report what would be fetched, which branch would be created and whether the remote advertises the pull request, without changing anything."}
				  },
				  "required": ["number"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		if (!EGit.isAvailable()) {
			return McpToolResult.error(EGit.NOT_INSTALLED);
		}
		// the JGit types live in a nested class, which is only loaded and verified once EGit is known to be there
		return Fetch.call(arguments, monitor);
	}

	private static final class Fetch {

		static McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
			ToolArguments args = ToolArguments.of(arguments);
			int number = args.getInt("number", 0, 0, Integer.MAX_VALUE); //$NON-NLS-1$
			if (number < 1) {
				return McpToolResult.error("Give the pull request 'number', a positive integer."); //$NON-NLS-1$
			}
			Repository repository;
			try {
				repository = EGit.lookup(args.getString("project"), args.getString("directory")); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (IOException e) {
				throw new McpToolException("Could not open the repository", e); //$NON-NLS-1$
			}
			if (repository == null) {
				return McpToolResult.error(
						"No git repository found. Give 'project' for a project in the workspace, or 'directory' for a working tree on disk."); //$NON-NLS-1$
			}
			String remoteName = args.getString("remote", Constants.DEFAULT_REMOTE_NAME); //$NON-NLS-1$
			URIish uri;
			try {
				RemoteConfig remote = new RemoteConfig(repository.getConfig(), remoteName);
				if (remote.getURIs().isEmpty()) {
					return McpToolResult.error("The repository has no remote '%s'. Configured remotes: %s." //$NON-NLS-1$
							.formatted(remoteName, remoteNames(repository)));
				}
				uri = remote.getURIs().get(0);
			} catch (URISyntaxException e) {
				throw new McpToolException("Could not read the remote configuration", e); //$NON-NLS-1$
			}

			String ref = "refs/pull/" + number + "/head"; //$NON-NLS-1$ //$NON-NLS-2$
			GitHubPullRequests.Answer api = GitHubPullRequests.read(uri, number, API_TIMEOUT_SECONDS);
			String branch = args.getString("branch"); //$NON-NLS-1$
			String branchSource;
			if (branch != null && !branch.isBlank()) {
				branch = branch.strip();
				branchSource = "argument"; //$NON-NLS-1$
			} else if (api.pullRequest() != null && api.pullRequest().headBranch() != null) {
				branch = api.pullRequest().headBranch();
				branchSource = "pullRequest"; //$NON-NLS-1$
			} else {
				branch = "pr-" + number; //$NON-NLS-1$
				branchSource = "fallback"; //$NON-NLS-1$
			}
			String branchRef = Constants.R_HEADS + branch;
			if (!Repository.isValidRefName(branchRef)) {
				return McpToolResult.error("'%s' is not a valid branch name.".formatted(branch)); //$NON-NLS-1$
			}
			boolean checkout = args.getBoolean("checkout", true); //$NON-NLS-1$
			boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$

			Ref existing;
			String fromBranch;
			String fromHead;
			try {
				existing = repository.exactRef(branchRef);
				fromBranch = repository.getBranch();
				ObjectId head = repository.resolve(Constants.HEAD);
				fromHead = head == null ? null : head.name();
			} catch (IOException e) {
				throw new McpToolException("Could not read the repository", e); //$NON-NLS-1$
			}
			boolean onBranch = branchRef.equals(fullBranch(repository));

			JsonObject result = new JsonObject()
					.put("directory", repository.getWorkTree() == null ? null : repository.getWorkTree().getAbsolutePath()) //$NON-NLS-1$
					.put("number", Integer.valueOf(number)) //$NON-NLS-1$
					.put("remote", remoteName) //$NON-NLS-1$
					.put("remoteUrl", uri.toString()) //$NON-NLS-1$
					.put("ref", ref) //$NON-NLS-1$
					.put("branch", branch) //$NON-NLS-1$
					.put("branchSource", branchSource) //$NON-NLS-1$
					.put("branchExists", Boolean.valueOf(existing != null)) //$NON-NLS-1$
					.put("fromBranch", fromBranch) //$NON-NLS-1$
					.put("fromHead", fromHead) //$NON-NLS-1$
					.put("pullRequest", describe(api.pullRequest())) //$NON-NLS-1$
					.put("apiError", api.error()) //$NON-NLS-1$
					.put("apiAuthenticated", Boolean.valueOf(api.authenticated())) //$NON-NLS-1$
					.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$

			if (dryRun) {
				return dryRun(result, uri, ref, existing, onBranch, checkout);
			}

			FetchOperation fetch = new FetchOperation(repository, uri,
					List.of(new RefSpec().setSource(ref).setDestination(Constants.FETCH_HEAD)),
					CallBudget.maxWaitSeconds(), false);
			try {
				fetch.run(monitor);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause() == null ? e : e.getCause();
				return McpToolResult.error("The fetch of %s from '%s' failed: %s".formatted(ref, uri, cause.getMessage())); //$NON-NLS-1$
			}
			FetchResult fetched = fetch.getOperationResult();
			Ref advertised = fetched == null ? null : fetched.getAdvertisedRef(ref);
			if (advertised == null || advertised.getObjectId() == null) {
				return McpToolResult.error(
						"'%s' does not advertise %s, so there is no pull request %d there. Check the number and that '%s' points at the repository the pull request was opened against." //$NON-NLS-1$
								.formatted(uri, ref, Integer.valueOf(number), remoteName));
			}
			ObjectId commitId = advertised.getObjectId();
			result.put("headSha", commitId.name()); //$NON-NLS-1$

			String branchAction;
			try {
				if (existing == null) {
					RevCommit commit;
					try (RevWalk walk = new RevWalk(repository)) {
						commit = walk.parseCommit(commitId);
					}
					new CreateLocalBranchOperation(repository, branch, commit).execute(monitor);
					branchAction = "created"; //$NON-NLS-1$
				} else if (commitId.equals(existing.getObjectId())) {
					branchAction = "unchanged"; //$NON-NLS-1$
				} else if (onBranch) {
					branchAction = fastForwardCheckedOut(repository, branch, number, monitor);
				} else {
					branchAction = fastForward(repository, branchRef, existing, commitId, number);
				}
			} catch (CoreException | IOException e) {
				throw new McpToolException("The commits were fetched, but the branch '%s' could not be updated" //$NON-NLS-1$
						.formatted(branch), e);
			}
			if (branchAction == null) {
				return McpToolResult.error(
						"The commits were fetched, but the local branch '%s' has commits that are not in the pull request, so it was left alone. Pass 'branch' to use another name." //$NON-NLS-1$
								.formatted(branch));
			}
			result.put("branchAction", branchAction); //$NON-NLS-1$

			if (!checkout || onBranch) {
				return McpToolResult.of(result.put("checkedOut", Boolean.valueOf(onBranch)) //$NON-NLS-1$
						.put("note", onBranch ? "The working tree was already on the branch and now has the pull request's commits." //$NON-NLS-1$ //$NON-NLS-2$
								: "The branch was fetched and not checked out. Switch with eclipse_checkout when needed.") //$NON-NLS-1$
						.toString());
			}
			BranchOperation switchTo = new BranchOperation(repository, branch);
			try {
				switchTo.execute(monitor);
			} catch (CoreException e) {
				return McpToolResult.error("The branch '%s' was %s, but checking it out failed: %s" //$NON-NLS-1$
						.formatted(branch, branchAction, e.getMessage()));
			}
			CheckoutResult outcome = switchTo.getResult(repository);
			JsonArray conflicts = new JsonArray();
			if (outcome != null && outcome.getConflictList() != null) {
				outcome.getConflictList().forEach(conflicts::add);
			}
			boolean ok = outcome == null || outcome.getStatus() == CheckoutResult.Status.OK;
			return McpToolResult.of(result.put("checkedOut", Boolean.valueOf(ok)) //$NON-NLS-1$
					.put("checkoutStatus", outcome == null ? null : outcome.getStatus().name()) //$NON-NLS-1$
					.put("conflicts", conflicts) //$NON-NLS-1$
					.put("note", ok //$NON-NLS-1$
							? "The affected projects were refreshed as part of the switch, so problem markers describe the pull request once a build has run." //$NON-NLS-1$
							: "The branch holds the pull request, but the switch did not complete. The working tree is as it was, and the conflicting paths are listed.") //$NON-NLS-1$
					.toString());
		}

		private static McpToolResult dryRun(JsonObject result, URIish uri, String ref, Ref existing, boolean onBranch,
				boolean checkout) {
			String remoteError = null;
			String headSha = null;
			try {
				Ref advertised = Git.lsRemoteRepository().setRemote(uri.toString())
						.setTimeout(CallBudget.maxWaitSeconds()).callAsMap().get(ref);
				headSha = advertised == null || advertised.getObjectId() == null ? null : advertised.getObjectId().name();
			} catch (Exception e) {
				remoteError = e.getMessage();
			}
			String branchAction;
			if (existing == null) {
				branchAction = "create"; //$NON-NLS-1$
			} else if (headSha != null && headSha.equals(existing.getObjectId().name())) {
				branchAction = "unchanged"; //$NON-NLS-1$
			} else {
				branchAction = "fastForwardIfPossible"; //$NON-NLS-1$
			}
			String note;
			if (remoteError != null) {
				note = "Nothing was fetched. The remote could not be asked whether it advertises the pull request; the fetch would fail the same way."; //$NON-NLS-1$
			} else if (headSha == null) {
				note = "Nothing was fetched. The remote does not advertise %s, so the fetch would fail; check the number and the remote." //$NON-NLS-1$
						.formatted(ref);
			} else {
				note = "Nothing was fetched. Pass dryRun false to fetch the pull request into the branch."; //$NON-NLS-1$
			}
			return McpToolResult.of(result.put("headSha", headSha) //$NON-NLS-1$
					.put("advertised", Boolean.valueOf(headSha != null)) //$NON-NLS-1$
					.put("remoteError", remoteError) //$NON-NLS-1$
					.put("branchAction", branchAction) //$NON-NLS-1$
					.put("wouldCheckout", Boolean.valueOf(checkout && !onBranch)) //$NON-NLS-1$
					.put("note", note) //$NON-NLS-1$
					.toString());
		}

		/** Fast-forwards a branch that is not checked out; {@code null} when it is not an ancestor. */
		private static String fastForward(Repository repository, String branchRef, Ref existing, ObjectId commitId,
				int number) throws IOException {
			RefUpdate update = repository.updateRef(branchRef);
			update.setNewObjectId(commitId);
			update.setExpectedOldObjectId(existing.getObjectId());
			update.setRefLogMessage("fetch pull request " + number, false); //$NON-NLS-1$
			return switch (update.update()) {
			case FAST_FORWARD -> "fastForwarded"; //$NON-NLS-1$
			case NO_CHANGE -> "unchanged"; //$NON-NLS-1$
			case REJECTED -> null;
			default -> throw new IOException("Updating the branch answered " + update.getResult()); //$NON-NLS-1$
			};
		}

		/** Fast-forwards the checked out branch through EGit, so the working tree follows; {@code null} when refused. */
		private static String fastForwardCheckedOut(Repository repository, String branch, int number,
				IProgressMonitor monitor) throws CoreException {
			MergeOperation merge = new MergeOperation(repository, Constants.FETCH_HEAD);
			merge.setFastForwardMode(FastForwardMode.FF_ONLY);
			merge.setMessage("fetch pull request " + number + " into " + branch); //$NON-NLS-1$ //$NON-NLS-2$
			merge.execute(monitor);
			MergeResult result = merge.getResult();
			if (result == null) {
				return null;
			}
			return switch (result.getMergeStatus()) {
			case FAST_FORWARD -> "fastForwarded"; //$NON-NLS-1$
			case ALREADY_UP_TO_DATE -> "unchanged"; //$NON-NLS-1$
			default -> null;
			};
		}

		private static JsonObject describe(GitHubPullRequests.PullRequest pr) {
			if (pr == null) {
				return null;
			}
			return new JsonObject().put("title", pr.title()).put("state", pr.state()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("draft", Boolean.valueOf(pr.draft())).put("author", pr.author()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("headRepository", pr.headRepository()).put("headBranch", pr.headBranch()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("headSha", pr.headSha()).put("baseBranch", pr.baseBranch()).put("url", pr.url()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		private static String fullBranch(Repository repository) {
			try {
				return repository.getFullBranch();
			} catch (IOException e) {
				return null;
			}
		}

		private static String remoteNames(Repository repository) {
			try {
				return RemoteConfig.getAllRemoteConfigs(repository.getConfig()).stream().map(RemoteConfig::getName)
						.toList().toString();
			} catch (URISyntaxException e) {
				return "unreadable"; //$NON-NLS-1$
			}
		}
	}
}
