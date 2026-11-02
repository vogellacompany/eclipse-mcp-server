package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The packages of the application being profiled, so that a profile can tell its work from
 * the platform and libraries beneath it.
 * <p>
 * Derived from the running product where that is not Eclipse itself, or given by the caller,
 * which is the only source for a recording of another JVM.
 */
public record ProductFamily(String label, List<String> prefixes, String source) {

	/** Takes {@code productPrefixes} from a tool's arguments; {@code runningProduct} also asks this IDE's product. */
	public static ProductFamily of(Map<String, Object> arguments, boolean runningProduct) {
		List<String> given = arguments != null && arguments.get("productPrefixes") instanceof List<?> list //$NON-NLS-1$
				? list.stream().map(String::valueOf).toList()
				: List.of();
		if (runningProduct) {
			try {
				IProduct product = Platform.getProduct();
				if (product != null) {
					return of(given, product.getName(), product.getId(), product.getApplication(),
							product.getDefiningBundle() == null ? null : product.getDefiningBundle().getSymbolicName());
				}
			} catch (RuntimeException | LinkageError e) {
				// no running platform, which leaves what the caller gave
			}
		}
		return of(given, null);
	}

	/** The family from a product's identifiers and the caller's prefixes, or null when neither names one. */
	public static ProductFamily of(List<String> given, String productName, String... productIdentifiers) {
		Set<String> derived = new LinkedHashSet<>();
		for (String identifier : productIdentifiers) {
			String prefix = vendorPrefix(identifier);
			if (prefix != null) {
				derived.add(prefix);
			}
		}
		Set<String> prefixes = new LinkedHashSet<>(derived);
		for (String prefix : given) {
			String trimmed = prefix.strip();
			if (!trimmed.isEmpty()) {
				prefixes.add(trimmed.endsWith(".") ? trimmed : trimmed + '.'); //$NON-NLS-1$
			}
		}
		if (prefixes.isEmpty()) {
			return null;
		}
		boolean named = !derived.isEmpty() && productName != null && !productName.isBlank();
		String source = derived.isEmpty() ? "productPrefixes" //$NON-NLS-1$
				: given.isEmpty() ? "running product" : "running product and productPrefixes"; //$NON-NLS-1$ //$NON-NLS-2$
		return new ProductFamily(named ? productName : "Application", new ArrayList<>(prefixes), source); //$NON-NLS-1$
	}

	/** The first two segments of an identifier, unless they are Eclipse's, this server's or the JDK's. */
	private static String vendorPrefix(String identifier) {
		if (identifier == null) {
			return null;
		}
		int first = identifier.indexOf('.');
		int second = first < 0 ? -1 : identifier.indexOf('.', first + 1);
		if (second < 0) {
			return null;
		}
		String prefix = identifier.substring(0, second + 1);
		boolean notAProduct = "org.eclipse.".equals(prefix) || "com.vogella.".equals(prefix) //$NON-NLS-1$ //$NON-NLS-2$
				|| ServerFrames.isJdk(identifier);
		return notAProduct ? null : prefix;
	}

	/** The family as an answer reports it. */
	public JsonObject toJson() {
		JsonArray list = new JsonArray();
		prefixes.forEach(list::add);
		return new JsonObject().put("label", label).put("prefixes", list).put("source", source); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
