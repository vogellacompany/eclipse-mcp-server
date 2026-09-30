package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools.Capture;
import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools.Capture.Paintable;

/**
 * Records a shell or a part as a sequence of PNG frames.
 * <p>
 * Each frame is painted on the UI thread through {@code Control.print}, the
 * path {@code eclipse_screenshot} falls back to, since reading the root
 * drawable is blank under a compositing window manager. The paint is the only
 * part that has to happen there: scaling and encoding run on a thread of the
 * session's own, so a frame costs the UI thread one print and nothing else.
 */
public final class Screencast {

	private static final Screencast INSTANCE = new Screencast();

	private static final int KEEP = 20;

	private final Map<String, Session> sessions = new LinkedHashMap<>();

	private int counter;

	private Screencast() {
	}

	public static Screencast getInstance() {
		return INSTANCE;
	}

	/** One recording, from its first frame until stop. */
	public static final class Session {

		private final String id;
		private final Control control;
		private final boolean composed;
		private final String target;
		private final int intervalMillis;
		private int maxFrames;
		private final int maxWidth;
		private final Path directory;
		private final long startedAt = System.currentTimeMillis();
		private volatile ExecutorService encoder = newEncoder();
		private final List<Long> timestamps = new ArrayList<>();
		private volatile String caption;
		private volatile String captionPosition = OVER;
		private volatile Rectangle crop;
		/** Wall clock time taken out between segments, so a pause is not a frame held for minutes. */
		private long correction;
		private int segments = 1;
		private final AtomicInteger written = new AtomicInteger();
		private final AtomicInteger failed = new AtomicInteger();
		private volatile String lastFailure;
		private volatile boolean running = true;
		private volatile String stoppedBy;
		private long armedAt;
		private int lateTicks;
		private long maxLatenessMillis;
		private long paintMillis;
		private volatile int zoom = 100;
		private volatile String frameSize;

		private volatile int frameWidth;

		Session(String id, Control control, boolean composed, String target, int intervalMillis, int maxFrames,
				int maxWidth, Path directory) {
			this.id = id;
			this.control = control;
			this.composed = composed;
			this.target = target;
			this.intervalMillis = intervalMillis;
			this.maxFrames = maxFrames;
			this.maxWidth = maxWidth;
			this.directory = directory;
		}

		private static ExecutorService newEncoder() {
			return Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "MCP screencast encoder"); //$NON-NLS-1$
				thread.setDaemon(true);
				return thread;
			});
		}

		public String id() {
			return id;
		}

		public String target() {
			return target;
		}

		public Control control() {
			return control;
		}

		public String caption() {
			return caption;
		}

		public String captionPosition() {
			return captionPosition;
		}

		public Rectangle crop() {
			return crop;
		}

		public synchronized int segments() {
			return segments;
		}

		public synchronized int maxFrames() {
			return maxFrames;
		}

		void configure(Rectangle cropRegion, String text, String position) {
			this.crop = cropRegion;
			this.caption = text;
			this.captionPosition = position == null ? OVER : position;
		}

		/**
		 * Continues a stopped recording as one more segment in the same directory.
		 * The pause between the segments is shown as {@code gapMillis}, because the
		 * frames stay on screen for the time between them and a pause of minutes
		 * would otherwise be a frame held for minutes.
		 */
		synchronized void resume(int extraFrames, int gapMillis, String text, String position) {
			if (running) {
				return;
			}
			if (position != null) {
				captionPosition = position;
			}
			long last = timestamps.isEmpty() ? startedAt : timestamps.get(timestamps.size() - 1).longValue();
			long now = System.currentTimeMillis() - correction;
			correction += Math.max(0, now - last - gapMillis);
			armedAt = 0;
			maxFrames = timestamps.size() + extraFrames;
			caption = text;
			segments++;
			running = true;
			stoppedBy = null;
			encoder = newEncoder();
		}

		public boolean running() {
			return running;
		}

		public String stoppedBy() {
			return stoppedBy;
		}

		public int intervalMillis() {
			return intervalMillis;
		}

		public int maxWidth() {
			return maxWidth;
		}

		public Path directory() {
			return directory;
		}

		public long startedAt() {
			return startedAt;
		}

		public int zoom() {
			return zoom;
		}

		public int frameWidth() {
			return frameWidth;
		}

		/** The width the frames are written at, which is the painted width unless maxWidth caps it. */
		public int outputWidth() {
			return outputWidth(frameWidth);
		}

		private int outputWidth(int painted) {
			return maxWidth <= 0 || painted <= maxWidth ? painted : Capture.crispWidth(painted, maxWidth);
		}

		public String frameSize() {
			return frameSize;
		}

		public synchronized int frames() {
			return timestamps.size();
		}

		public int written() {
			return written.get();
		}

		public int failed() {
			return failed.get();
		}

		public String lastFailure() {
			return lastFailure;
		}

		public synchronized int lateTicks() {
			return lateTicks;
		}

		public synchronized long maxLatenessMillis() {
			return maxLatenessMillis;
		}

		/** What one frame cost the UI thread on average. */
		public synchronized long averagePaintMillis() {
			return timestamps.isEmpty() ? 0 : paintMillis / timestamps.size();
		}

		public synchronized long elapsedMillis() {
			return (timestamps.isEmpty() ? System.currentTimeMillis() : timestamps.get(timestamps.size() - 1))
					- startedAt;
		}

		/** How long each frame stayed on screen, from the recorded timestamps. */
		public synchronized int[] delaysMillis() {
			int[] delays = new int[timestamps.size()];
			for (int i = 0; i < delays.length; i++) {
				long next = i + 1 < delays.length ? timestamps.get(i + 1).longValue() : timestamps.get(i).longValue() + intervalMillis;
				delays[i] = (int) Math.max(1, next - timestamps.get(i).longValue());
			}
			return delays;
		}

		public Path frame(int index) {
			return directory.resolve("frame-%04d.png".formatted(Integer.valueOf(index))); //$NON-NLS-1$
		}

		/** Ends the recording; the reason is what the answer reports. */
		public void stop(String reason) {
			if (running) {
				running = false;
				stoppedBy = reason;
				encoder.shutdown();
			}
		}

		/** Waits for the frames still being encoded, at most the given seconds. */
		public boolean awaitEncoded(int seconds) {
			try {
				return encoder.awaitTermination(seconds, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/** Paints one frame. UI thread only. */
		void tick(Display display) {
			if (!running) {
				return;
			}
			if (control.isDisposed()) {
				stop("disposed"); //$NON-NLS-1$
				return;
			}
			long now = System.currentTimeMillis() - correction;
			int index;
			synchronized (this) {
				// lateness is how long the timer waited past its due time for the UI
				// thread, not the paint, which is measured separately
				if (armedAt > 0) {
					long lateness = now - armedAt - intervalMillis;
					if (lateness > intervalMillis) {
						lateTicks++;
					}
					maxLatenessMillis = Math.max(maxLatenessMillis, lateness);
				}
				index = timestamps.size();
			}
			ImageData data;
			try {
				Frame painted = paint(display, control, composed, crop, caption, captionPosition);
				data = painted.data();
				zoom = painted.zoom();
				frameSize = data.width + "x" + data.height; //$NON-NLS-1$
				frameWidth = data.width;
			} catch (RuntimeException | Error e) {
				stop("error: " + e); //$NON-NLS-1$
				return;
			}
			synchronized (this) {
				timestamps.add(Long.valueOf(now));
				paintMillis += System.currentTimeMillis() - correction - now;
				armedAt = System.currentTimeMillis() - correction;
			}
			encoder.execute(() -> encode(data, index));
			if (frames() >= maxFrames) {
				stop("maxFrames"); //$NON-NLS-1$
				return;
			}
			display.timerExec(intervalMillis, () -> tick(display));
		}

		private void encode(ImageData data, int index) {
			try {
				ImageData scaled = data;
				int snapped = outputWidth(data.width);
				if (data.width > snapped) {
					scaled = data.scaledTo(snapped, Math.max(1, data.height * snapped / data.width));
				}
				ImageLoader loader = new ImageLoader();
				loader.data = new ImageData[] { scaled };
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				loader.save(bytes, SWT.IMAGE_PNG);
				Files.write(frame(index), bytes.toByteArray());
				written.incrementAndGet();
			} catch (IOException | RuntimeException e) {
				failed.incrementAndGet();
				lastFailure = String.valueOf(e);
			}
		}
	}

	/** One painted frame; the image is kept only while a crop or caption still needs it. */
	record Frame(ImageData data, int zoom, Image image) {
	}

	public static final String OVER = "over"; //$NON-NLS-1$

	public static final String ABOVE = "above"; //$NON-NLS-1$

	public static final String BELOW = "below"; //$NON-NLS-1$

	private static final int CAPTION_PAD = 8;

	/**
	 * The part of a frame a crop keeps, as the intersection with the canvas, or
	 * {@code null} when nothing of it lies inside.
	 */
	public static Rectangle clampCrop(Rectangle crop, int width, int height) {
		if (crop == null) {
			return null;
		}
		Rectangle kept = crop.intersection(new Rectangle(0, 0, width, height));
		return kept.width <= 0 || kept.height <= 0 ? null : kept;
	}

	/**
	 * Paints the control, keeps the crop region and draws the caption: over the
	 * bottom of the picture, or on a bar added above or below it, which is what
	 * keeps a cropped editor's page tabs readable.
	 */
	static Frame paint(Display display, Control printable, boolean composed, Rectangle crop, String caption,
			String position) {
		Frame full = paintWhole(display, printable, composed);
		Rectangle own = composed ? ((Shell) printable).getClientArea() : printable.getBounds();
		Capture.Size canvas = Capture.compositionSize(own.width, own.height);
		Rectangle region = clampCrop(crop, canvas.width(), canvas.height());
		if (region == null && caption == null) {
			full.image().dispose();
			return new Frame(full.data(), full.zoom(), null);
		}
		Rectangle kept = region == null ? new Rectangle(0, 0, canvas.width(), canvas.height()) : region;
		boolean outside = caption != null && !OVER.equals(position);
		int bar = caption == null ? 0 : captionBarHeight(display, caption);
		int added = outside ? bar : 0;
		int pictureY = outside && ABOVE.equals(position) ? bar : 0;
		try {
			Image framed = DeviceScale.paint(display, kept.width, kept.height + added, full.zoom(), (gc, width, height) -> {
				// the frame already holds device pixels, so it is placed rather than
				// drawn through the canvas' scale, while the caption is painted at it
				DeviceScale.drawPixels(gc, full.image(), -kept.x, pictureY - kept.y, full.zoom());
				if (caption != null) {
					int barY = ABOVE.equals(position) && outside ? 0 : height - bar;
					drawCaption(gc, caption, barY, width, bar, outside);
				}
			});
			try {
				return new Frame(DeviceScale.paintedData(framed, full.zoom()), full.zoom(), null);
			} finally {
				framed.dispose();
			}
		} finally {
			full.image().dispose();
		}
	}

	private static int captionBarHeight(Display display, String caption) {
		GC probe = new GC(display);
		try {
			return probe.textExtent(caption).y + 2 * CAPTION_PAD;
		} finally {
			probe.dispose();
		}
	}

	private static void drawCaption(GC gc, String caption, int y, int width, int bar, boolean opaque) {
		gc.setAlpha(opaque ? 255 : 180);
		gc.setBackground(gc.getDevice().getSystemColor(SWT.COLOR_BLACK));
		gc.fillRectangle(0, y, width, bar);
		gc.setAlpha(255);
		gc.setForeground(gc.getDevice().getSystemColor(SWT.COLOR_WHITE));
		gc.drawText(caption, CAPTION_PAD, y + CAPTION_PAD, SWT.DRAW_TRANSPARENT);
	}

	/**
	 * Paints the control at its monitor's zoom. A shell is composed from its
	 * children the way the screenshot does, because {@code Shell.print} is blank
	 * under a compositing window manager while the children print fine.
	 */
	private static Frame paintWhole(Display display, Control printable, boolean composed) {
		int zoom = DeviceScale.zoomOf(printable);
		Rectangle own = composed ? ((Shell) printable).getClientArea() : printable.getBounds();
		Capture.Size canvas = Capture.compositionSize(own.width, own.height);
		List<Paintable> pieces = composed ? Capture.paintablesOf((Shell) printable) : null;
		Control backgroundSource = pieces != null && !pieces.isEmpty() ? pieces.get(0).control() : printable;
		Color background = backgroundOf(backgroundSource);
		Image image = DeviceScale.paint(display, canvas.width(), canvas.height(), zoom, (drawer, width, height) -> {
			drawer.setBackground(background);
			drawer.fillRectangle(0, 0, width, height);
			if (pieces == null) {
				printable.print(drawer);
				// on GTK 3 a print leaves the on-screen widget unpainted until it is invalidated
				if (DeviceScale.GTK) {
					Rectangle bounds = printable.getBounds();
					printable.redraw(0, 0, bounds.width, bounds.height, true);
				}
				return;
			}
			for (Paintable piece : pieces) {
				Image part = DeviceScale.paint(display, piece.at().width, piece.at().height, zoom, (gc, w, h) -> {
					gc.setBackground(backgroundOf(piece.control()));
					gc.fillRectangle(0, 0, w, h);
					piece.control().print(gc);
					if (DeviceScale.GTK) {
						piece.control().redraw(0, 0, piece.at().width, piece.at().height, true);
					}
				});
				try {
					DeviceScale.drawPixels(drawer, part, piece.at().x, piece.at().y, zoom);
				} finally {
					part.dispose();
				}
			}
		});
		return new Frame(DeviceScale.paintedData(image, zoom), zoom, image);
	}

	private static Color backgroundOf(Control control) {
		Color own = control.getBackground();
		return own == null ? control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND) : own;
	}

	/** Starts recording and paints the first frame. UI thread only. */
	public synchronized Session start(Display display, Control control, boolean composed, String target,
			int intervalMillis, int maxFrames, int maxWidth, Path directory, Rectangle crop, String caption,
			String captionPosition) {
		String id = "screencast-" + (++counter); //$NON-NLS-1$
		Session session = new Session(id, control, composed, target, intervalMillis, maxFrames, maxWidth,
				directory);
		session.configure(crop, caption, captionPosition);
		sessions.put(id, session);
		while (sessions.size() > KEEP) {
			String oldest = sessions.keySet().iterator().next();
			sessions.remove(oldest).stop("evicted"); //$NON-NLS-1$
		}
		session.tick(display);
		return session;
	}

	/** Continues a stopped session and paints the first frame of the new segment. UI thread only. */
	public synchronized Session resume(Display display, Session session, int extraFrames, int gapMillis,
			String caption, String captionPosition) {
		session.resume(extraFrames, gapMillis, caption, captionPosition);
		session.tick(display);
		return session;
	}

	/** The most recently started session that is no longer recording, or {@code null}. */
	public synchronized Session findLatestStopped() {
		Session latest = null;
		for (Session session : sessions.values()) {
			if (!session.running()) {
				latest = session;
			}
		}
		return latest;
	}

	public synchronized Session find(String id) {
		return sessions.get(id);
	}

	public synchronized List<String> ids() {
		return new ArrayList<>(sessions.keySet());
	}

	public synchronized Session findLatest() {
		Session latest = null;
		for (Session session : sessions.values()) {
			latest = session;
		}
		return latest;
	}
}
