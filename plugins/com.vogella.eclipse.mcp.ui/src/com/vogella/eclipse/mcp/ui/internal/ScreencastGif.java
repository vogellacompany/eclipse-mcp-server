package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;

/**
 * Assembles frames into an animated GIF through SWT's own image loader.
 * <p>
 * Every frame is mapped onto a fixed 6x7x6 colour cube plus greys: coarser than an adaptive
 * palette, but deterministic and single pass. The PNG frames stay lossless on disk.
 */
public final class ScreencastGif {

	private static final int LEVELS = 6;
	private static final int CUBE = LEVELS * LEVELS * LEVELS;
	private static final int GREYS = 256 - CUBE;
	private static final PaletteData PALETTE = palette();

	private ScreencastGif() {
	}

	private static PaletteData palette() {
		RGB[] colors = new RGB[256];
		int i = 0;
		for (int r = 0; r < LEVELS; r++) {
			for (int g = 0; g < LEVELS; g++) {
				for (int b = 0; b < LEVELS; b++) {
					colors[i++] = new RGB(level(r), level(g), level(b));
				}
			}
		}
		// a grey ramp beside the cube, because an IDE is mostly greys and a cube
		// with six levels turns a mid grey into a tinted one
		for (int k = 0; k < GREYS; k++) {
			int grey = k * 255 / (GREYS - 1);
			colors[i++] = new RGB(grey, grey, grey);
		}
		return new PaletteData(colors);
	}

	private static int level(int index) {
		return index * 255 / (LEVELS - 1);
	}

	/** The nearer of the cube entry and the grey ramp entry. */
	private static int index(int r, int g, int b) {
		int ri = (r * (LEVELS - 1) + 127) / 255;
		int gi = (g * (LEVELS - 1) + 127) / 255;
		int bi = (b * (LEVELS - 1) + 127) / 255;
		int cube = (ri * LEVELS + gi) * LEVELS + bi;
		int cubeDistance = square(r - level(ri)) + square(g - level(gi)) + square(b - level(bi));
		int luminance = (r * 299 + g * 587 + b * 114) / 1000;
		int gk = (luminance * (GREYS - 1) + 127) / 255;
		int grey = gk * 255 / (GREYS - 1);
		int greyDistance = square(r - grey) + square(g - grey) + square(b - grey);
		return greyDistance < cubeDistance ? CUBE + gk : cube;
	}

	private static int square(int value) {
		return value * value;
	}

	/** The frame mapped onto the fixed palette, 8 bits deep. */
	public static ImageData quantize(ImageData source) {
		ImageData indexed = new ImageData(source.width, source.height, 8, PALETTE);
		int[] row = new int[source.width];
		int[] out = new int[source.width];
		PaletteData palette = source.palette;
		for (int y = 0; y < source.height; y++) {
			source.getPixels(0, y, source.width, row, 0);
			for (int x = 0; x < source.width; x++) {
				RGB rgb = palette.isDirect ? direct(palette, row[x]) : palette.getRGB(row[x]);
				out[x] = index(rgb.red, rgb.green, rgb.blue);
			}
			indexed.setPixels(0, y, source.width, out, 0);
		}
		return indexed;
	}

	/** {@link PaletteData#getRGB} without the mask bookkeeping the caller has already paid for. */
	private static RGB direct(PaletteData palette, int pixel) {
		return new RGB(component(pixel, palette.redMask, palette.redShift),
				component(pixel, palette.greenMask, palette.greenShift),
				component(pixel, palette.blueMask, palette.blueShift));
	}

	private static int component(int pixel, int mask, int shift) {
		int value = pixel & mask;
		return shift < 0 ? value >>> -shift : value << shift;
	}

	/**
	 * Writes the frames as one looping or single-pass animation. {@code delaysMillis}
	 * is how long each frame stays, which GIF stores in hundredths of a second.
	 */
	public static long write(List<ImageData> frames, int[] delaysMillis, boolean loop, Path out) throws IOException {
		if (frames.isEmpty()) {
			throw new IOException("There are no frames to write"); //$NON-NLS-1$
		}
		ImageData[] indexed = new ImageData[frames.size()];
		int width = 0;
		int height = 0;
		for (int i = 0; i < indexed.length; i++) {
			indexed[i] = quantize(frames.get(i));
			indexed[i].delayTime = Math.max(1, (delaysMillis[i] + 5) / 10);
			indexed[i].disposalMethod = SWT.DM_FILL_NONE;
			width = Math.max(width, indexed[i].width);
			height = Math.max(height, indexed[i].height);
		}
		ImageLoader loader = new ImageLoader();
		loader.data = indexed;
		loader.logicalScreenWidth = width;
		loader.logicalScreenHeight = height;
		loader.repeatCount = loop ? 0 : 1;
		try {
			loader.save(out.toString(), SWT.IMAGE_GIF);
		} catch (SWTException e) {
			throw new IOException("Could not write the GIF: " + e.getMessage(), e); //$NON-NLS-1$
		}
		return Files.size(out);
	}

	/** Reads one PNG frame back, as written by the recording. */
	public static ImageData read(Path png) throws IOException {
		try (InputStream in = Files.newInputStream(png)) {
			ImageData[] loaded = new ImageLoader().load(in);
			if (loaded.length == 0) {
				throw new IOException("No image in " + png); //$NON-NLS-1$
			}
			return loaded[0];
		} catch (SWTException e) {
			throw new IOException("Could not read " + png + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
