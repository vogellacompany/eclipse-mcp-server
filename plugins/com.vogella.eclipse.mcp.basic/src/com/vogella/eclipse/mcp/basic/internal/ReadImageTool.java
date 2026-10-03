package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Returns an image file from the workspace as image content, so the model sees the picture.
 */
public final class ReadImageTool implements IMcpTool {

	/** 5 MB once base64 encoded, which is where common clients start refusing an image. */
	private static final int DEFAULT_MAX_BYTES = 3_750_000;

	@Override
	public String getName() {
		return "eclipse_read_image"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns a PNG, JPEG, GIF or WebP file from the workspace as an image you can look at, together with its format, size in bytes and pixel dimensions. Read-only. Use it for icons, screenshots in documentation, splash screens and anything else checked into a project; eclipse_screenshot is the tool for what the IDE shows right now. The format is read from the file's content, not its extension. SVG is text and is read with eclipse_read_file; BMP and ICO are refused because clients do not accept them as images."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path":     {"type":"string","description":"Workspace path of the image, e.g. /app/icons/sample.png"},
				    "maxBytes": {"type":"integer","minimum":1,"maximum":20000000,"default":3750000,"description":"Refuse rather than return a larger file. The default is 5 MB once encoded, which is what common clients accept."},
				    "refresh":  {"type":"boolean","default":true,"description":"Read outside changes into the workspace first."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}
		IPath workspacePath = IPath.fromPortableString(path);
		IFile file = workspacePath.segmentCount() < 2 ? null
				: ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
		if (file != null && args.getBoolean("refresh", true)) { //$NON-NLS-1$
			try {
				FileSupport.refresh(file, monitor);
			} catch (CoreException e) {
				// still readable
			}
		}
		if (file == null || !file.exists()) {
			return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
		}
		int maxBytes = args.getInt("maxBytes", DEFAULT_MAX_BYTES, 1, 20_000_000); //$NON-NLS-1$
		byte[] bytes;
		try (InputStream in = file.getContents(true)) {
			bytes = in.readNBytes(maxBytes + 1);
		} catch (CoreException | IOException e) {
			return McpToolResult.error("Could not read '%s': %s".formatted(path, e.getMessage())); //$NON-NLS-1$
		}
		if (bytes.length > maxBytes) {
			return McpToolResult.error("'%s' is larger than maxBytes (%d). Raise it if your client accepts larger images." //$NON-NLS-1$
					.formatted(path, Integer.valueOf(maxBytes)));
		}
		Format format = Format.of(bytes);
		if (format == null) {
			return McpToolResult.error(unsupported(path, bytes));
		}
		JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
				.put("mimeType", format.mimeType) //$NON-NLS-1$
				.put("bytes", Integer.valueOf(bytes.length)); //$NON-NLS-1$
		int[] size = format.dimensions(bytes);
		if (size != null) {
			result.put("width", Integer.valueOf(size[0])).put("height", Integer.valueOf(size[1])); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString()).withImage(bytes, format.mimeType);
	}

	private static String unsupported(String path, byte[] bytes) {
		String start = new String(bytes, 0, Math.min(bytes.length, 256), java.nio.charset.StandardCharsets.ISO_8859_1)
				.stripLeading();
		if (start.startsWith("<svg") || start.startsWith("<?xml")) { //$NON-NLS-1$ //$NON-NLS-2$
			return "'%s' is SVG, which is text: read it with eclipse_read_file.".formatted(path); //$NON-NLS-1$
		}
		if (bytes.length > 2 && bytes[0] == 'B' && bytes[1] == 'M') {
			return "'%s' is a BMP, which clients do not accept as an image. Only PNG, JPEG, GIF and WebP are returned." //$NON-NLS-1$
					.formatted(path);
		}
		return "'%s' is not a PNG, JPEG, GIF or WebP image, judging by its content.".formatted(path); //$NON-NLS-1$
	}

	enum Format {
		PNG("image/png"), JPEG("image/jpeg"), GIF("image/gif"), WEBP("image/webp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

		final String mimeType;

		Format(String mimeType) {
			this.mimeType = mimeType;
		}

		static Format of(byte[] b) {
			if (b.length >= 24 && u8(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
				return PNG;
			}
			if (b.length >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) {
				return JPEG;
			}
			if (b.length >= 10 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
				return GIF;
			}
			if (b.length >= 16 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W'
					&& b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
				return WEBP;
			}
			return null;
		}

		/** Width and height read from the header, or null when the header does not say. */
		int[] dimensions(byte[] b) {
			return switch (this) {
			case PNG -> new int[] { be32(b, 16), be32(b, 20) };
			case GIF -> new int[] { u8(b, 6) | u8(b, 7) << 8, u8(b, 8) | u8(b, 9) << 8 };
			case JPEG -> jpeg(b);
			case WEBP -> webp(b);
			};
		}

		private static int[] jpeg(byte[] b) {
			int i = 2;
			while (i + 9 < b.length) {
				if (u8(b, i) != 0xFF) {
					return null;
				}
				int marker = u8(b, i + 1);
				if (marker == 0xFF) {
					i++;
					continue;
				}
				// SOF0 to SOF15, except DHT, JPG and DAC, which share the range
				if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
					return new int[] { u8(b, i + 7) << 8 | u8(b, i + 8), u8(b, i + 5) << 8 | u8(b, i + 6) };
				}
				i += 2 + (u8(b, i + 2) << 8 | u8(b, i + 3));
			}
			return null;
		}

		private static int[] webp(byte[] b) {
			if (b.length < 30) {
				return null;
			}
			String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
			return switch (chunk) {
			case "VP8X" -> new int[] { 1 + (u8(b, 24) | u8(b, 25) << 8 | u8(b, 26) << 16), //$NON-NLS-1$
					1 + (u8(b, 27) | u8(b, 28) << 8 | u8(b, 29) << 16) };
			case "VP8L" -> { //$NON-NLS-1$
				int bits = u8(b, 21) | u8(b, 22) << 8 | u8(b, 23) << 16 | u8(b, 24) << 24;
				yield new int[] { 1 + (bits & 0x3FFF), 1 + (bits >>> 14 & 0x3FFF) };
			}
			case "VP8 " -> new int[] { (u8(b, 26) | u8(b, 27) << 8) & 0x3FFF, (u8(b, 28) | u8(b, 29) << 8) & 0x3FFF }; //$NON-NLS-1$
			default -> null;
			};
		}

		private static int u8(byte[] b, int i) {
			return b[i] & 0xFF;
		}

		private static int be32(byte[] b, int i) {
			return u8(b, i) << 24 | u8(b, i + 1) << 16 | u8(b, i + 2) << 8 | u8(b, i + 3);
		}
	}
}
