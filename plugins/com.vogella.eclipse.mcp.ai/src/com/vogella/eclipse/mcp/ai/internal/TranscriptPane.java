package com.vogella.eclipse.mcp.ai.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * A read-only transcript of a session that runs in a terminal outside this IDE.
 */
final class TranscriptPane {

	private static final int MAX_MESSAGES = 400;

	private final StyledText text;
	private ClaudeSession session;
	private Job loading;

	TranscriptPane(Composite parent) {
		text = new StyledText(parent, SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
		text.setMargins(16, 12, 16, 12);
		text.addDisposeListener(event -> {
			if (loading != null) {
				loading.cancel();
			}
		});
	}

	Control control() {
		return text;
	}

	ClaudeSession session() {
		return session;
	}

	/** Shows the transcript of a session, or reloads it when it is the one already shown. */
	void show(ClaudeSession shown) {
		boolean sameSession = session != null && session.sessionId() != null
				&& session.sessionId().equals(shown.sessionId());
		session = shown;
		load(!sameSession);
	}

	private void load(boolean scrollToEnd) {
		if (loading != null) {
			loading.cancel();
		}
		ClaudeSession shown = session;
		Job job = Job.create(Messages.readingTranscript, (IProgressMonitor monitor) -> {
			Path file = Transcript.locate(shown);
			String failure = null;
			Transcript.Contents contents = null;
			if (file == null) {
				failure = Messages.transcriptMissing(shown);
			} else {
				try {
					contents = Transcript.read(file, MAX_MESSAGES);
				} catch (IOException e) {
					failure = e.getMessage();
				}
			}
			if (monitor.isCanceled()) {
				return Status.CANCEL_STATUS;
			}
			Transcript.Contents read = contents;
			String error = failure;
			text.getDisplay().asyncExec(() -> {
				if (!text.isDisposed() && shown == session) {
					render(read, error, scrollToEnd);
				}
			});
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		loading = job;
		job.schedule();
	}

	private void render(Transcript.Contents contents, String error, boolean scrollToEnd) {
		if (contents == null) {
			text.setText(error);
			text.setStyleRanges(new StyleRange[0]);
			return;
		}
		boolean atEnd = scrollToEnd || text.getTopIndex() + visibleLines() >= text.getLineCount() - 1;
		int topIndex = text.getTopIndex();
		StringBuilder content = new StringBuilder();
		List<StyleRange> styles = new ArrayList<>();
		if (contents.omitted() > 0) {
			content.append(Messages.olderMessagesOmitted(contents.omitted())).append("\n\n"); //$NON-NLS-1$
		}
		for (Transcript.Message message : contents.messages()) {
			String speaker = message.fromUser() ? Messages.speakerUser : Messages.speakerClaude;
			StyleRange style = new StyleRange();
			style.start = content.length();
			style.length = speaker.length();
			style.fontStyle = SWT.BOLD;
			if (message.fromUser()) {
				style.foreground = JFaceResources.getColorRegistry().get(JFacePreferences.ACTIVE_HYPERLINK_COLOR);
			}
			styles.add(style);
			content.append(speaker).append('\n').append(message.text()).append("\n\n"); //$NON-NLS-1$
		}
		if (contents.messages().isEmpty()) {
			content.append(Messages.transcriptEmpty);
		}
		text.setText(content.toString());
		text.setStyleRanges(styles.toArray(StyleRange[]::new));
		text.setTopIndex(atEnd ? Math.max(0, text.getLineCount() - 1) : topIndex);
	}

	private int visibleLines() {
		return text.getClientArea().height / Math.max(1, text.getLineHeight());
	}
}
