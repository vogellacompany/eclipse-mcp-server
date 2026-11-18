package com.vogella.eclipse.mcp.ui.internal;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.Bundle;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Records what the shells paint, through an SWT paint recorder when present, and renders the recording
 * into a video. Both the recorder and the renderer are optional and reached by name only, so that nothing here depends
 * on them; when either is missing, the screencast records frames instead.
 */
public final class PaintRecording {

	static final String RECORDER = "org.eclipse.swt.widgets.PaintRecorder"; //$NON-NLS-1$

	static final String RENDERER_BUNDLE = "com.vogella.swt.recording"; //$NON-NLS-1$

	static final String RENDERER = "com.vogella.swt.recording.RecordingFrames"; //$NON-NLS-1$

	private static final int KEEP = 20;

	private static final long GIF_MEMORY_BYTES = 200L * 1024 * 1024;

	private static final long FFMPEG_EXIT_SECONDS = 120;

	private static final PaintRecording INSTANCE = new PaintRecording();

	private final Map<String, Session> sessions = new LinkedHashMap<>();

	private int counter;

	private PaintRecording() {
	}

	public static PaintRecording getInstance() {
		return INSTANCE;
	}

	/** Why paint recording cannot be used here, or {@code null} when it can. */
	public static String unavailableReason() {
		Class<?> recorder = recorderClass();
		if (recorder == null) {
			return "paint recording is not available in this SWT"; //$NON-NLS-1$
		}
		try {
			if (Boolean.TRUE.equals(recorder.getMethod("isSupported").invoke(null))) { //$NON-NLS-1$
				return null;
			}
			Object reason = recorder.getMethod("unsupportedReason").invoke(null); //$NON-NLS-1$
			return reason == null ? "the paint recorder is not supported here" : reason.toString(); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return "the paint recorder could not be asked: " + rootMessage(e); //$NON-NLS-1$
		}
	}

	/** Why a paint recording cannot be turned into a video here, or {@code null} when it can. */
	public static String rendererMissingReason() {
		return rendererClass() == null ? "the paint recording renderer is not installed" : null; //$NON-NLS-1$
	}

	private static Class<?> recorderClass() {
		try {
			return Class.forName(RECORDER, false, Display.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}

	private static Class<?> rendererClass() {
		try {
			Bundle bundle = Platform.getBundle(RENDERER_BUNDLE);
			return bundle == null ? null : bundle.loadClass(RENDERER);
		} catch (ClassNotFoundException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static String rootMessage(Throwable e) {
		Throwable cause = e instanceof InvocationTargetException target && target.getCause() != null ? target.getCause() : e;
		return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** One paint recording, from start until its video is rendered. */
	public static final class Session {

		private final String id;
		private final String shellTitle;
		private final Shell shell;
		private final Display display;
		private volatile String titleAtStop;
		private volatile boolean videoWritten;
		private final OutputStream out;
		private final Class<?> recorderType;
		private final Path directory;
		private final Path recording;
		private final long startedAt = System.currentTimeMillis();
		private final Object recorder;
		private volatile long stoppedAt;
		private volatile String stoppedBy;
		private volatile boolean stopRequested;
		/** Completes once the recorder is stopped and the file closed, so a render never reads a file still written. */
		private final CompletableFuture<Void> stopped = new CompletableFuture<>();
		private CompletableFuture<JsonObject> export;
		private Options exportedWith;

		Session(String id, Shell shell, Path directory, Path recording, Class<?> recorderType, Object recorder,
				OutputStream out) {
			this.id = id;
			this.shell = shell;
			this.display = shell.getDisplay();
			this.shellTitle = shell.getText();
			this.recorderType = recorderType;
			this.out = out;
			this.directory = directory;
			this.recording = recording;
			this.recorder = recorder;
		}

		public String id() {
			return id;
		}

		public String shellTitle() {
			return shellTitle;
		}

		public Path directory() {
			return directory;
		}

		public Path recording() {
			return recording;
		}

		public long startedAt() {
			return startedAt;
		}

		public boolean running() {
			return !stopped.isDone();
		}

		public String stoppedBy() {
			return stoppedBy;
		}

		public long elapsedMillis() {
			return (stoppedAt == 0 ? System.currentTimeMillis() : stoppedAt) - startedAt;
		}

		/** Ends the recording. UI thread only. */
		void stop(String reason) {
			if (stopRequested) {
				return;
			}
			stopRequested = true;
			stoppedAt = System.currentTimeMillis();
			titleAtStop = shell.isDisposed() ? null : shell.getText();
			String outcome = reason;
			try {
				recorderType.getMethod("stop").invoke(recorder); //$NON-NLS-1$
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				outcome = reason + ", and stopping the recorder failed: " + rootMessage(e); //$NON-NLS-1$
			} finally {
				finish(outcome);
			}
		}

		private void finish(String outcome) {
			try {
				out.close();
			} catch (IOException e) {
				// the recorder closed it, or the file is already complete
			}
			stoppedBy = outcome;
			stopped.complete(null);
		}

		/** Waits at most the given milliseconds for the stop to have finished. */
		boolean awaitStopped(long millis) {
			try {
				stopped.get(millis, TimeUnit.MILLISECONDS);
				return true;
			} catch (TimeoutException | ExecutionException e) {
				return stopped.isDone();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		void stopFromAnyThread(String reason) {
			if (display.getThread() == Thread.currentThread()) {
				stop(reason);
				return;
			}
			try {
				display.asyncExec(() -> stop(reason));
			} catch (SWTException e) {
				// the display is gone, and the recorder with it
				stopRequested = true;
				stoppedAt = System.currentTimeMillis();
				finish(reason);
			}
		}

		/**
		 * Starts rendering the video on a thread of its own, and returns that work; a render under way is joined, a
		 * finished one is reused only when it succeeded with the same options.
		 */
		synchronized CompletableFuture<JsonObject> export(Options options) {
			if (export != null && export.isDone() && !(videoWritten && options.equals(exportedWith))) {
				export = null;
				videoWritten = false;
			}
			if (export == null) {
				exportedWith = options;
				export = CompletableFuture.supplyAsync(() -> render(this, options), runnable -> {
					Thread thread = new Thread(runnable, "MCP paint recording export"); //$NON-NLS-1$
					thread.setDaemon(true);
					thread.start();
				});
			}
			return export;
		}
	}

	/** How a recording is rendered. */
	public record Options(int fps, int zoom, boolean allShells, boolean titleBars, boolean pointer, int maxGifFrames,
			boolean loop, Path output) {
	}

	/** Starts a recording of every shell of the display. UI thread only. */
	public synchronized Session start(Display display, Shell shell, Path directory, int maxSeconds)
			throws IOException {
		Class<?> recorderType = recorderClass();
		if (recorderType == null) {
			throw new IllegalStateException(unavailableReason());
		}
		String id = "recording-" + (++counter); //$NON-NLS-1$
		Path file = directory.resolve(stem(id) + ".bin"); //$NON-NLS-1$
		Object recorder;
		OutputStream out = Files.newOutputStream(file);
		try {
			Method start = recorderType.getMethod("start", Display.class, OutputStream.class); //$NON-NLS-1$
			recorder = start.invoke(null, display, out);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			out.close();
			throw new IOException("The paint recorder did not start: " + rootMessage(e), e); //$NON-NLS-1$
		}
		Session session = new Session(id, shell, directory, file, recorderType, recorder, out);
		sessions.put(id, session);
		while (sessions.size() > KEEP) {
			String oldest = sessions.keySet().iterator().next();
			Session evicted = sessions.remove(oldest);
			if (evicted.running()) {
				evicted.stop("evicted"); //$NON-NLS-1$
			}
		}
		display.timerExec((int) TimeUnit.SECONDS.toMillis(maxSeconds), () -> session.stop("maxSeconds")); //$NON-NLS-1$
		return session;
	}

	/** Stops every running recording, on its display's thread, for a plug-in that is going away. */
	public synchronized void stopAll(String reason) {
		for (Session session : sessions.values()) {
			if (session.running()) {
				session.stopFromAnyThread(reason);
			}
		}
	}

	/** A file name stem no other recording uses, also after a restart restarts the ids, in a shared directory. */
	private static String stem(String id) {
		return "paint-%s-%d".formatted(id, Long.valueOf(System.currentTimeMillis())); //$NON-NLS-1$
	}

	public synchronized Session find(String id) {
		return sessions.get(id);
	}

	public synchronized Session findLatest() {
		Session latest = null;
		for (Session session : sessions.values()) {
			latest = session;
		}
		return latest;
	}

	public synchronized List<String> ids() {
		return new ArrayList<>(sessions.keySet());
	}

	/** Waits at most the given seconds for the video and returns what was rendered, or {@code null} on timeout. */
	public static JsonObject awaitExport(CompletableFuture<JsonObject> export, int seconds) {
		try {
			return export.get(seconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			return new JsonObject().put("videoPath", null).put("videoError", rootMessage(e.getCause())); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static JsonObject render(Session session, Options options) {
		JsonObject result = new JsonObject();
		String missing = rendererMissingReason();
		if (missing != null) {
			return result.put("videoPath", null).put("videoError", //$NON-NLS-1$ //$NON-NLS-2$
					"The recording was written, but it cannot be turned into a video here: " + missing + "."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			Class<?> type = rendererClass();
			Object frames = type.getMethod("open", Path.class).invoke(null, session.recording()); //$NON-NLS-1$
			@SuppressWarnings("unchecked")
			Map<Integer, String> shells = (Map<Integer, String>) type.getMethod("getShells").invoke(frames); //$NON-NLS-1$
			long durationMicros = ((Long) type.getMethod("getDurationMicros").invoke(frames)).longValue(); //$NON-NLS-1$
			if (shells.isEmpty()) {
				return result.put("videoPath", null).put("videoError", "Nothing was painted while recording."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			Integer shell = null;
			boolean shellFound = true;
			if (options.allShells()) {
				frames = type.getMethod("allShells").invoke(frames); //$NON-NLS-1$
			} else {
				ShellChoice choice = chooseShell(shells, session.titleAtStop, session.shellTitle());
				shell = choice.id();
				shellFound = choice.found();
				frames = type.getMethod("shell", int.class).invoke(frames, shell); //$NON-NLS-1$
			}
			if (options.titleBars()) {
				frames = type.getMethod("titleBars").invoke(frames); //$NON-NLS-1$
			}
			Boolean pointerDrawn = null;
			if (options.pointer()) {
				try {
					frames = type.getMethod("pointer").invoke(frames); //$NON-NLS-1$
					pointerDrawn = Boolean.TRUE;
				} catch (NoSuchMethodException e) {
					// a renderer from before pointer recordings
					pointerDrawn = Boolean.FALSE;
				}
			}
			if (options.zoom() > 0) {
				frames = type.getMethod("zoom", int.class).invoke(frames, Integer.valueOf(options.zoom())); //$NON-NLS-1$
			}
			// an output named .gif asks for a GIF whatever is installed
			boolean gifRequested = options.output() != null
					&& options.output().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif"); //$NON-NLS-1$
			Path ffmpeg = gifRequested ? null : findFfmpeg();
			Point size = (Point) type.getMethod("getSize").invoke(frames); //$NON-NLS-1$
			int fps = options.fps();
			if (ffmpeg == null && durationMicros > 0) {
				// a GIF holds every frame in memory, so its rate is lowered to fit maxFrames and the memory budget
				long pixels = Math.max(1L, (long) size.x * size.y);
				long frameCap = Math.min(options.maxGifFrames(), Math.max(1L, GIF_MEMORY_BYTES / pixels));
				long fitting = frameCap * 1_000_000L / durationMicros;
				fps = (int) Math.max(1, Math.min(fps, fitting));
			}
			frames = type.getMethod("fps", int.class).invoke(frames, Integer.valueOf(fps)); //$NON-NLS-1$
			result.put("shells", Integer.valueOf(shells.size())) //$NON-NLS-1$
					.put("recordedShell", shell == null ? null : shells.get(shell)) //$NON-NLS-1$
					.put("shellNote", shellFound ? null //$NON-NLS-1$
							: "No recorded shell has the title '%s' the recording started on, so the video shows the shell that painted first; pass allShells true to see every shell." //$NON-NLS-1$
									.formatted(session.shellTitle()))
					.put("durationMillis", Long.valueOf(durationMicros / 1000)) //$NON-NLS-1$
					.put("fps", Integer.valueOf(fps)) //$NON-NLS-1$
					.put("pointer", pointerDrawn) //$NON-NLS-1$
					.put("frameSize", size.x + "x" + size.y); //$NON-NLS-1$ //$NON-NLS-2$
			if (ffmpeg != null && !gifRequested) {
				Path output = options.output() != null ? options.output() : sibling(session, ".mp4"); //$NON-NLS-1$
				int count = encodeMp4(ffmpeg, type, frames, size, fps, output, sibling(session, "-ffmpeg.log")); //$NON-NLS-1$
				session.videoWritten = true;
				return result.put("videoPath", output.toAbsolutePath().toString()).put("videoFormat", "mp4") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.put("videoFrames", Integer.valueOf(count)).put("videoBytes", Long.valueOf(Files.size(output))); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Path output = options.output() == null ? sibling(session, ".gif") : asGif(options.output()); //$NON-NLS-1$
			Files.createDirectories(output.toAbsolutePath().getParent());
			List<ImageData> images = new ArrayList<>();
			Consumer<ImageData> collect = image -> images.add(ScreencastGif.quantize(image));
			type.getMethod("forEachImage", Consumer.class).invoke(frames, collect); //$NON-NLS-1$
			int[] delays = new int[images.size()];
			Arrays.fill(delays, 1000 / fps);
			long bytes = ScreencastGif.writeIndexed(images, delays, options.loop(), output);
			session.videoWritten = true;
			return result.put("videoPath", output.toAbsolutePath().toString()).put("videoFormat", "gif") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("videoFrames", Integer.valueOf(images.size())).put("videoBytes", Long.valueOf(bytes)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("videoNote", gifRequested ? null //$NON-NLS-1$
							: "No ffmpeg was found on the PATH, so the video is a GIF at %d frames a second; install ffmpeg for a smooth MP4." //$NON-NLS-1$
									.formatted(Integer.valueOf(fps)));
		} catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
			return result.put("videoPath", null).put("videoError", "Rendering the recording failed: " + rootMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/** The path with a .gif name, since without ffmpeg a path named for another format still gets a GIF. */
	private static Path asGif(Path output) {
		String name = output.getFileName().toString();
		if (name.toLowerCase(Locale.ROOT).endsWith(".gif")) { //$NON-NLS-1$
			return output;
		}
		int dot = name.lastIndexOf('.');
		return output.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".gif"); //$NON-NLS-1$
	}

	private static Path sibling(Session session, String suffix) {
		String name = session.recording().getFileName().toString();
		return session.recording().resolveSibling(name.substring(0, name.length() - ".bin".length()) + suffix); //$NON-NLS-1$
	}

	/** The shell a single-shell video shows, and whether it was found by title rather than taken as the first. */
	public record ShellChoice(Integer id, boolean found) {
	}

	/** The shell titled as at stop, else as at start (exactly, then as a substring), else the first recorded. */
	public static ShellChoice chooseShell(Map<Integer, String> shells, String titleAtStop, String titleAtStart) {
		Integer match = matchShell(shells, titleAtStop);
		if (match == null) {
			match = matchShell(shells, titleAtStart);
		}
		return match != null ? new ShellChoice(match, true) : new ShellChoice(shells.keySet().iterator().next(), false);
	}

	/** The shell with this title, else the first whose title contains it, else {@code null}. */
	static Integer matchShell(Map<Integer, String> shells, String title) {
		if (title != null) {
			for (Map.Entry<Integer, String> entry : shells.entrySet()) {
				if (title.equals(entry.getValue())) {
					return entry.getKey();
				}
			}
			for (Map.Entry<Integer, String> entry : shells.entrySet()) {
				if (entry.getValue() != null && !title.isEmpty() && entry.getValue().contains(title)) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	private static int encodeMp4(Path ffmpeg, Class<?> type, Object frames, Point size, int fps, Path output, Path log)
			throws IOException, ReflectiveOperationException {
		Files.createDirectories(output.toAbsolutePath().getParent());
		ProcessBuilder builder = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"-f", "rawvideo", "-pix_fmt", "bgra", "-s", size.x + "x" + size.y, "-r", Integer.toString(fps), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
				"-i", "-", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-pix_fmt", "yuv420p", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
				"-movflags", "+faststart", output.toString()) //$NON-NLS-1$ //$NON-NLS-2$
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(log.toFile());
		Process process = builder.start();
		int[] count = { 0 };
		boolean finished = false;
		try {
			try (OutputStream in = process.getOutputStream()) {
				Consumer<byte[]> write = bgra -> {
					try {
						in.write(bgra);
						count[0]++;
					} catch (IOException e) {
						// ends the renderer's loop instead of rendering frames nobody reads
						throw new UncheckedIOException(e);
					}
				};
				type.getMethod("forEachFrame", Consumer.class).invoke(frames, write); //$NON-NLS-1$
			} catch (InvocationTargetException e) {
				if (!(e.getCause() instanceof UncheckedIOException)) {
					throw e;
				}
			}
			if (!process.waitFor(FFMPEG_EXIT_SECONDS, TimeUnit.SECONDS)) {
				throw new IOException("ffmpeg did not finish within %d seconds".formatted(Long.valueOf(FFMPEG_EXIT_SECONDS))); //$NON-NLS-1$
			}
			int exit = process.exitValue();
			if (exit != 0) {
				String detail = Files.exists(log) ? Files.readString(log).strip() : ""; //$NON-NLS-1$
				throw new IOException("ffmpeg ended with %d%s".formatted(Integer.valueOf(exit), //$NON-NLS-1$
						detail.isEmpty() ? "" : ": " + detail.lines().reduce((a, b) -> b).orElse(""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			finished = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while ffmpeg encoded the video", e); //$NON-NLS-1$
		} finally {
			if (!finished) {
				process.destroyForcibly();
			}
		}
		Files.deleteIfExists(log);
		return count[0];
	}

	/** ffmpeg from the PATH, or from where package managers put it, since an IDE started from a desktop has a short PATH. */
	static Path findFfmpeg() {
		String name = FileLocations.isWindows() ? "ffmpeg.exe" : "ffmpeg"; //$NON-NLS-1$ //$NON-NLS-2$
		List<String> directories = new ArrayList<>();
		String path = System.getenv("PATH"); //$NON-NLS-1$
		if (path != null) {
			directories.addAll(List.of(path.split(File.pathSeparator)));
		}
		if (!FileLocations.isWindows()) {
			directories.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		for (String directory : directories) {
			if (directory.isBlank()) {
				continue;
			}
			Path candidate = Path.of(directory, name);
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate;
			}
		}
		return null;
	}
}
