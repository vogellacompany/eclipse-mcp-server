package com.vogella.eclipse.mcp.ai.internal;

/**
 * The texts of the AI perspective.
 */
final class Messages {

	static final String welcomeHeader = "Claude Code"; //$NON-NLS-1$
	static final String welcomeTitle = "Claude Code sessions"; //$NON-NLS-1$
	static final String welcomeHint = "Start a session in a project, or pick a running one from the panel. Sessions started here keep running while you look at another one."; //$NON-NLS-1$
	static final String readOnlySuffix = "  (read-only transcript)"; //$NON-NLS-1$
	static final String toggleSessions = "Sessions"; //$NON-NLS-1$
	static final String searchSessions = "Search sessions"; //$NON-NLS-1$
	static final String projectsSection = "Projects"; //$NON-NLS-1$
	static final String readingTranscript = "Reading Claude Code transcript"; //$NON-NLS-1$
	static final String transcriptEmpty = "The transcript has no messages yet."; //$NON-NLS-1$
	static final String speakerUser = "You"; //$NON-NLS-1$
	static final String speakerClaude = "Claude"; //$NON-NLS-1$
	static final String newSession = "New Session"; //$NON-NLS-1$
	static final String newSessionTooltip = "Start Claude Code in a project or folder"; //$NON-NLS-1$
	static final String chooseFolder = "Choose Folder..."; //$NON-NLS-1$
	static final String chooseFolderMessage = "Folder to start Claude Code in"; //$NON-NLS-1$
	static final String attach = "Attach to tmux Pane"; //$NON-NLS-1$
	static final String showTranscript = "Show Transcript"; //$NON-NLS-1$
	static final String closeTerminal = "Close Terminal"; //$NON-NLS-1$
	static final String claudeNotFound = "Claude Code was not found on the PATH, in ~/.local/bin or in ~/.claude/local."; //$NON-NLS-1$
	static final String tmuxPaneGone = "tmux no longer knows the pane this session ran in."; //$NON-NLS-1$
	static final String statusStarting = "starting"; //$NON-NLS-1$
	static final String statusEnded = "ended"; //$NON-NLS-1$
	static final String notificationTitle = "Claude is waiting"; //$NON-NLS-1$
	static final String notificationShow = "<a>Show terminal</a>"; //$NON-NLS-1$
	static final String externalTooltip = "Started outside Eclipse: its screen lives in another terminal, so this shows its transcript."; //$NON-NLS-1$

	private Messages() {
	}

	static String transcriptMissing(ClaudeSession session) {
		return "No transcript yet for session " + session.sessionId() //$NON-NLS-1$
				+ ". Claude Code writes it with the first prompt."; //$NON-NLS-1$
	}

	static String olderMessagesOmitted(int count) {
		return "(" + count + " older messages not shown)"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String notificationText(String name, String folder) {
		return name + " in " + folder + " finished and waits for you."; //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String attachTitle(String target) {
		return "tmux " + target; //$NON-NLS-1$
	}
}
