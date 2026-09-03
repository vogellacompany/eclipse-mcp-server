package com.vogella.eclipse.mcp.ui.internal;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.css.core.engine.CSSEngine;
import org.eclipse.e4.ui.css.swt.dom.WidgetElement;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.PlatformUI;
import org.w3c.dom.Element;
import org.w3c.dom.css.CSSStyleDeclaration;
import org.w3c.dom.css.CSSStyleSheet;
import org.w3c.dom.css.ViewCSS;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Everything that talks to the e4 CSS engine.
 * <p>
 * The engine bundles are optional, so every reference to them lives in this one
 * class and callers catch {@link LinkageError}, the way {@code GitContent}
 * isolates jgit. The theme engine itself is reached reflectively: the two
 * methods a snippet needs, {@code resetCurrentTheme} and {@code getCSSEngines},
 * are on the internal implementation and not on {@code IThemeEngine}, which is
 * the same gap PDE's CSS scratch pad works around with a cast.
 */
final class CssStyling {

	/** The ad-hoc stylesheet applied on top of the theme, {@code null} when none is. */
	private static volatile String snippet;

	private CssStyling() {
	}

	/** The CSS view of a widget: what the engine calls it, and how a rule can name it. */
	static JsonObject describe(Widget widget) {
		JsonObject result = new JsonObject();
		try {
			result.put("cssId", WidgetElement.getID(widget)) //$NON-NLS-1$
					.put("cssClass", WidgetElement.getCSSClass(widget)); //$NON-NLS-1$
			CSSEngine engine = WidgetElement.getEngine(widget);
			if (engine == null) {
				return result.put("cssElement", null) //$NON-NLS-1$
						.put("cssNote", "No CSS engine is attached, so this widget is not styled by the theme engine."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Element element = engine.getElement(widget);
			result.put("cssElement", element == null ? null : element.getLocalName()); //$NON-NLS-1$
		} catch (LinkageError | RuntimeException e) {
			result.put("cssNote", //$NON-NLS-1$
					"The e4 CSS bundles are not available in this IDE, so only the SWT side can be reported."); //$NON-NLS-1$
		}
		return result;
	}

	/**
	 * What the engine resolved for a widget, and which of it a rule decided.
	 * <p>
	 * {@code computed} is the widget's live value, which a property handler reads
	 * back from SWT and which therefore always answers something; {@code declared}
	 * is what the matching rules set, and is the only way to tell a themed colour
	 * from the window system's default.
	 */
	static void styles(Widget widget, List<String> properties, String pseudo, JsonObject into) {
		CSSEngine engine;
		try {
			engine = WidgetElement.getEngine(widget);
		} catch (LinkageError | RuntimeException e) {
			into.put("computedNote", //$NON-NLS-1$
					"The e4 CSS bundles are not available in this IDE, so no computed values could be read."); //$NON-NLS-1$
			return;
		}
		if (engine == null) {
			into.put("computedNote", //$NON-NLS-1$
					"No CSS engine is attached to this widget, so nothing was computed for it."); //$NON-NLS-1$
			return;
		}
		if (engine.getElement(widget) == null) {
			// retrieveCSSProperty dereferences the element without checking, so a
			// widget the engine never wrapped has to be answered here
			into.put("computedNote", //$NON-NLS-1$
					"The CSS engine has no element for this widget, so nothing was computed for it."); //$NON-NLS-1$
			return;
		}
		Map<String, String> cascade = cascade(engine, widget, pseudo);
		JsonObject computed = new JsonObject();
		JsonObject declared = new JsonObject();
		JsonObject origin = new JsonObject();
		for (String property : properties) {
			String value = engine.retrieveCSSProperty(widget, property, pseudo);
			String rule = cascade.get(property);
			computed.put(property, value);
			declared.put(property, rule);
			origin.put(property, rule != null ? "css" : value != null ? "widget" : "unset"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		into.put("computed", computed) //$NON-NLS-1$
				.put("declared", declared) //$NON-NLS-1$
				.put("origin", origin) //$NON-NLS-1$
				.put("cssDeclaration", cascade.isEmpty() ? null : text(cascade)); //$NON-NLS-1$
	}

	/** The merged declaration the matching rules produce for this element. */
	private static Map<String, String> cascade(CSSEngine engine, Widget widget, String pseudo) {
		try {
			Element element = engine.getElement(widget);
			CSSStyleDeclaration declaration = element == null ? null : computedStyle(engine, element, pseudo);
			if (declaration == null) {
				return Map.of();
			}
			Map<String, String> values = new LinkedHashMap<>();
			for (int i = 0; i < declaration.getLength(); i++) {
				String name = declaration.item(i);
				values.put(name, declaration.getPropertyValue(name));
			}
			return values;
		} catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
			return Map.of();
		}
	}

	/**
	 * The cascade's declaration for an element. css.core 0.14.900 replaced
	 * {@code getViewCSS} with {@code computeStyle}, and neither name exists on
	 * both sides of that change, so each is reached by name.
	 */
	private static CSSStyleDeclaration computedStyle(CSSEngine engine, Element element, String pseudo)
			throws ReflectiveOperationException {
		try {
			Method compute = CSSEngine.class.getMethod("computeStyle", Element.class, String.class); //$NON-NLS-1$
			return (CSSStyleDeclaration) compute.invoke(engine, element, pseudo);
		} catch (NoSuchMethodException e) {
			Object view = CSSEngine.class.getMethod("getViewCSS").invoke(engine); //$NON-NLS-1$
			return view instanceof ViewCSS cascade ? cascade.getComputedStyle(element, pseudo) : null;
		}
	}

	private static String text(Map<String, String> cascade) {
		StringBuilder builder = new StringBuilder();
		cascade.forEach((name, value) -> builder.append(name).append(": ").append(value).append("; ")); //$NON-NLS-1$ //$NON-NLS-2$
		return builder.toString().strip();
	}

	/**
	 * Puts an ad-hoc stylesheet on top of the current theme, or takes it away.
	 * <p>
	 * The theme is re-applied first, so a snippet replaces the one before it rather
	 * than piling on top of it, and so dropping one needs nothing but that step.
	 */
	static JsonObject apply(String css, boolean drop) {
		long start = System.nanoTime();
		JsonObject result = new JsonObject();
		String previous = snippet;
		Object themeEngine = themeEngine();
		JsonArray errors = new JsonArray();
		boolean themeReset = false;
		if (themeEngine != null) {
			try {
				themeEngine.getClass().getMethod("resetCurrentTheme").invoke(themeEngine); //$NON-NLS-1$
				themeReset = true;
				snippet = null;
			} catch (ReflectiveOperationException | RuntimeException e) {
				errors.add("Resetting the theme failed: " + e); //$NON-NLS-1$
			}
		}
		if (drop && !themeReset) {
			return result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"The theme engine could not be reached, so a snippet cannot be taken back. Restarting the IDE drops it.")
					.put("errors", errors); //$NON-NLS-1$
		}
		List<CSSEngine> engines = engines(themeEngine);
		Parsed parsed = new Parsed(false, -1);
		if (!drop && engines.isEmpty()) {
			return result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"No CSS engine is attached to this display, so this IDE is not styled by the theme engine at all.")
					.put("errors", errors); //$NON-NLS-1$
		}
		if (!drop) {
			for (CSSEngine engine : engines) {
				Parsed one = parse(engine, css, errors);
				parsed = new Parsed(parsed.ok() || one.ok(), Math.max(parsed.rules(), one.rules()));
				try {
					engine.reapply();
				} catch (RuntimeException e) {
					errors.add("Re-applying the styles failed: " + e); //$NON-NLS-1$
				}
			}
			// a snippet the parser rejected is not in place, and saying it is would make
			// the next reset look like it had something to take back
			snippet = parsed.ok() ? css : null;
		}
		List<JsonObject> preferenceRules = new ArrayList<>();
		List<JsonObject> ignoredRules = new ArrayList<>();
		boolean preferencesApplied = true;
		if (!drop && parsed.ok()) {
			for (PreferenceRules.Rule rule : PreferenceRules.scan(css)) {
				if (themeEngine == null) {
					ignoredRules.add(new JsonObject().put("selector", rule.selector()) //$NON-NLS-1$
							.put("qualifier", rule.qualifier()) //$NON-NLS-1$
							.put("reason", IGNORED_NOTE)); //$NON-NLS-1$
					continue;
				}
				PreferenceOutcome outcome = stylePreferences(themeEngine, rule);
				preferenceRules.add(outcome.json());
				preferencesApplied &= outcome.fullyApplied();
			}
		}
		result.put("applied", Boolean.valueOf(!drop && parsed.ok() && preferencesApplied)) //$NON-NLS-1$
				.put("previousSnippet", previous) //$NON-NLS-1$
				.put("theme", activeThemeId(themeEngine)) //$NON-NLS-1$
				.put("themeReapplied", Boolean.valueOf(themeReset)) //$NON-NLS-1$
				.put("engines", Integer.valueOf(engines.size())) //$NON-NLS-1$
				.put("rules", parsed.rules() < 0 ? null : Integer.valueOf(parsed.rules())) //$NON-NLS-1$
				.put("errors", errors) //$NON-NLS-1$
				.put("elapsedMillis", Long.valueOf((System.nanoTime() - start) / 1_000_000L)); //$NON-NLS-1$
		if (!ignoredRules.isEmpty()) {
			result.put("ignoredRules", ignoredRules); //$NON-NLS-1$
		}
		if (!preferenceRules.isEmpty()) {
			result.put("preferenceRules", preferenceRules); //$NON-NLS-1$
		}
		result.put("note", preferenceRules.isEmpty() ? note(themeReset) : PREFERENCE_NOTE + " " + note(themeReset)); //$NON-NLS-1$ //$NON-NLS-2$
		return result;
	}

	private static String note(boolean themeReset) {
		return themeReset ? LIFETIME_NOTE
				: "The theme engine could not be reached, so the snippet was added without re-applying the theme first and can only be taken back by restarting the IDE."; //$NON-NLS-1$
	}

	private static final String LIFETIME_NOTE = "The snippet lives in memory only. It is gone on the next theme change, on eclipse_restart, and when this plug-in stops."; //$NON-NLS-1$

	private static final String PREFERENCE_NOTE = "IEclipsePreferences blocks were styled through the theme engine's own preference path; preferenceRules says what took effect, and a theme change takes their overrides back like any other."; //$NON-NLS-1$

	/** What styling one preference block came to: the report and whether every key took. */
	private record PreferenceOutcome(JsonObject json, boolean fullyApplied) {
	}

	private static final String IGNORED_NOTE = "Preference rules take effect when a theme is activated, which is the one thing this tool cannot do, and the theme engine could not be reached here either, so this block was not styled. eclipse_set_theme activates a theme and applies such blocks outright."; //$NON-NLS-1$

	/**
	 * Styles one {@code IEclipsePreferences} block through the same call the
	 * workbench makes when a theme changes, then reads the keys back.
	 * <p>
	 * The engine leaves a value it did not set itself alone until the theme has
	 * changed once this session, so verification is what makes the answer honest:
	 * an unchanged key is reported as unchanged, with the value that is there now.
	 */
	private static PreferenceOutcome stylePreferences(Object themeEngine, PreferenceRules.Rule rule) {
		JsonObject outcome = new JsonObject().put("selector", rule.selector()) //$NON-NLS-1$
				.put("qualifier", rule.qualifier()); //$NON-NLS-1$
		JsonArray applied = new JsonArray();
		JsonArray unchanged = new JsonArray();
		boolean recognised = true;
		try {
			org.eclipse.core.runtime.preferences.IEclipsePreferences node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
					.getNode(rule.qualifier());
			// the same entry point StylingPreferencesHandler drives on every theme change,
			// which keeps the backup bookkeeping for overridden values in platform code
			// rather than here
			themeEngine.getClass().getMethod("applyStyles", Object.class, boolean.class).invoke(themeEngine, node, //$NON-NLS-1$
					Boolean.FALSE);
			if (rule.values().isEmpty()) {
				recognised = false;
				outcome.put("reason", "No 'key=value' pair was recognised in the block."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			for (Map.Entry<String, String> pair : rule.values().entrySet()) {
				String actual = node.get(pair.getKey(), null);
				if (pair.getValue().equals(actual)) {
					applied.add(pair.getKey());
				} else {
					unchanged.add(new JsonObject().put("key", pair.getKey()) //$NON-NLS-1$
							.put("requested", pair.getValue()) //$NON-NLS-1$
							.put("current", actual)); //$NON-NLS-1$
				}
			}
		} catch (InvocationTargetException e) {
			return new PreferenceOutcome(
					outcome.put("error", "Styling the preferences failed: " + e.getCause()), false); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new PreferenceOutcome(outcome.put("error", "Styling the preferences failed: " + e), false); //$NON-NLS-1$ //$NON-NLS-2$
		}
		outcome.put("appliedKeys", applied).put("unchangedKeys", unchanged); //$NON-NLS-1$ //$NON-NLS-2$
		if (unchanged.size() > 0) {
			outcome.put("note", "These keys kept their value: the theme engine overwrites a value it did not set itself only once the theme has changed this session. Activating another theme with eclipse_set_theme opens them up."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new PreferenceOutcome(outcome,
				recognised && unchanged.size() == 0 && applied.size() == rule.values().size());
	}

	/** Whether a snippet parsed, and how many rules it produced where that can be counted. */
	private record Parsed(boolean ok, int rules) {
	}

	/**
	 * Parses a snippet into an engine, which is what puts it into the cascade.
	 * <p>
	 * Every engine call here is by name: {@code parseStyleSheet} changed its
	 * return type, and css.core 0.14.900 removed the error handler in favour of
	 * {@code getProblems} on the parsed sheet. This bundle is compiled against
	 * one release and run on another, so both shapes have to work.
	 */
	private static Parsed parse(CSSEngine engine, String css, JsonArray errors) {
		List<Exception> reported = new ArrayList<>();
		Diverted diverted = divertErrors(engine, reported);
		try {
			// Appended last, so the snippet wins the cascade ties it is written to win.
			Object sheet = CSSEngine.class.getMethod("parseStyleSheet", Reader.class).invoke(engine, //$NON-NLS-1$
					new StringReader(css));
			problems(sheet).forEach(problem -> errors.add(String.valueOf(problem)));
			return new Parsed(true, rules(sheet));
		} catch (InvocationTargetException e) {
			// e4's parser throws unchecked as well, and the message carries line and column
			errors.add("The snippet was not applied: " + e.getCause()); //$NON-NLS-1$
			return new Parsed(false, -1);
		} catch (ReflectiveOperationException | RuntimeException e) {
			errors.add("The snippet was not applied: " + e); //$NON-NLS-1$
			return new Parsed(false, -1);
		} finally {
			reported.forEach(e -> errors.add(String.valueOf(e)));
			if (diverted != null) {
				diverted.restore(engine);
			}
		}
	}

	/** An engine's previous error handler, kept so it can be put back. */
	private record Diverted(Method setter, Object previous) {
		void restore(CSSEngine engine) {
			try {
				setter.invoke(engine, previous);
			} catch (ReflectiveOperationException | RuntimeException e) {
				// the handler was this call's own, and the engine logs on its own without one
			}
		}
	}

	/**
	 * Points an engine's error handler at a list, where the engine still has one.
	 * Null on an engine without the handler API, which logs through ILog instead.
	 */
	private static Diverted divertErrors(CSSEngine engine, List<Exception> into) {
		try {
			Method getter = CSSEngine.class.getMethod("getErrorHandler"); //$NON-NLS-1$
			Class<?> type = getter.getReturnType();
			Method setter = CSSEngine.class.getMethod("setErrorHandler", type); //$NON-NLS-1$
			Object handler = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
					(proxy, method, args) -> {
						if (args != null && args.length == 1 && args[0] instanceof Exception e) {
							into.add(e);
							return null;
						}
						return switch (method.getName()) {
						case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
						case "equals" -> proxy == args[0]; //$NON-NLS-1$
						case "toString" -> "eclipse_apply_css error handler"; //$NON-NLS-1$ //$NON-NLS-2$
						default -> null;
						};
					});
			Object previous = getter.invoke(engine);
			setter.invoke(engine, handler);
			return new Diverted(setter, previous);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** The malformed rules a parsed sheet reports, where the sheet can say. */
	private static Collection<?> problems(Object sheet) {
		try {
			return sheet.getClass().getMethod("getProblems").invoke(sheet) instanceof Collection<?> problems //$NON-NLS-1$
					? problems
					: List.of();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return List.of();
		}
	}

	/** How many rules a parsed sheet holds, under either spelling of a stylesheet. */
	private static int rules(Object sheet) {
		if (sheet instanceof CSSStyleSheet parsed) {
			return parsed.getCssRules().getLength();
		}
		try {
			return sheet.getClass().getMethod("getRules").invoke(sheet) instanceof Collection<?> parsed //$NON-NLS-1$
					? parsed.size()
					: -1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return -1;
		}
	}

	/** The engines the theme engine drives, or the one this display is styled by. */
	private static List<CSSEngine> engines(Object themeEngine) {
		List<CSSEngine> engines = new ArrayList<>();
		if (themeEngine != null) {
			try {
				if (themeEngine.getClass().getMethod("getCSSEngines").invoke(themeEngine) instanceof Collection<?> known) { //$NON-NLS-1$
					known.forEach(each -> {
						if (each instanceof CSSEngine engine) {
							engines.add(engine);
						}
					});
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// falls through to the display's own engine
			}
		}
		if (engines.isEmpty()) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			CSSEngine engine = display == null ? null : WidgetElement.getEngine(display);
			if (engine != null) {
				engines.add(engine);
			}
		}
		return engines;
	}

	/**
	 * The theme engine of the running workbench, {@code null} when there is none.
	 * <p>
	 * Looked up by name rather than by type: the interface is exported to a friends
	 * list this bundle is not on, and nothing here needs to compile against it.
	 */
	private static Object themeEngine() {
		try {
			IEclipseContext context = PlatformUI.getWorkbench().getService(IEclipseContext.class);
			return context == null ? null : context.get("org.eclipse.e4.ui.css.swt.theme.IThemeEngine"); //$NON-NLS-1$
		} catch (LinkageError | RuntimeException e) {
			return null;
		}
	}

	/** The id of the CSS theme in force, which is a different thing from the workbench ITheme. */
	static String activeCssThemeId() {
		return activeThemeId(themeEngine());
	}

	private static String activeThemeId(Object themeEngine) {
		if (themeEngine == null) {
			return null;
		}
		try {
			Object theme = themeEngine.getClass().getMethod("getActiveTheme").invoke(themeEngine); //$NON-NLS-1$
			return theme == null ? null : String.valueOf(theme.getClass().getMethod("getId").invoke(theme)); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** One registered theme, read off the engine without compiling against it. */
	record ThemeRef(String id, String label) {

		JsonObject describe() {
			return new JsonObject().put("id", id).put("label", label); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Every theme the engine has registered, empty when it cannot be reached. */
	static List<ThemeRef> registeredThemes(Object themeEngine) {
		if (themeEngine == null) {
			return List.of();
		}
		try {
			Object result = themeEngine.getClass().getMethod("getThemes").invoke(themeEngine); //$NON-NLS-1$
			List<ThemeRef> themes = new ArrayList<>();
			if (result instanceof Collection<?> known) {
				for (Object theme : known) {
					String id = stringOf(theme, "getId"); //$NON-NLS-1$
					if (id != null) {
						themes.add(new ThemeRef(id, stringOf(theme, "getLabel"))); //$NON-NLS-1$
					}
				}
			}
			return themes;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return List.of();
		}
	}

	private static String stringOf(Object target, String method) {
		if (target == null) {
			return null;
		}
		try {
			Object value = target.getClass().getMethod(method).invoke(target);
			return value == null ? null : String.valueOf(value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Narrows from exact id, to exact label, to substring over both, and stops at
	 * the first step that finds anything. The same ordering {@code ViewTools.match}
	 * uses for views.
	 */
	static List<ThemeRef> matchingThemes(List<ThemeRef> all, String wanted) {
		for (ThemeRef theme : all) {
			if (theme.id().equals(wanted)) {
				return List.of(theme);
			}
		}
		List<ThemeRef> byLabel = new ArrayList<>();
		for (ThemeRef theme : all) {
			if (wanted.equalsIgnoreCase(theme.label())) {
				byLabel.add(theme);
			}
		}
		if (!byLabel.isEmpty()) {
			return byLabel;
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<ThemeRef> partial = new ArrayList<>();
		for (ThemeRef theme : all) {
			if (theme.label() != null && theme.label().toLowerCase(Locale.ROOT).contains(needle)
					|| theme.id().toLowerCase(Locale.ROOT).contains(needle)) {
				partial.add(theme);
			}
		}
		return partial;
	}

	/** Every registered theme with which one is active, capped at {@code maxResults}. */
	static JsonObject listThemes(int maxResults) {
		Object engine = themeEngine();
		JsonArray themes = new JsonArray();
		List<ThemeRef> all = registeredThemes(engine);
		String active = activeThemeId(engine);
		for (ThemeRef theme : all.subList(0, Math.min(maxResults, all.size()))) {
			themes.add(theme.describe().put("active", Boolean.valueOf(theme.id().equals(active)))); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("themes", themes) //$NON-NLS-1$
				.put("total", Integer.valueOf(all.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(all.size() > maxResults)); //$NON-NLS-1$
		if (engine == null || all.isEmpty()) {
			result.put("reason", "The e4 CSS theme engine could not be reached, so no theme can be listed or switched."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result;
	}

	/**
	 * Activates a theme by id or label, optionally remembered across restarts.
	 * <p>
	 * The switch goes through the engine's own {@code setTheme(String, boolean)},
	 * which is also what makes an {@code IEclipsePreferences} block take effect:
	 * only the activation path styles the preference nodes.
	 */
	static JsonObject switchTheme(String wanted, boolean persist) {
		Object engine = themeEngine();
		if (engine == null) {
			return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The e4 CSS theme engine could not be reached."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<ThemeRef> all = registeredThemes(engine);
		List<ThemeRef> matches = matchingThemes(all, wanted);
		if (matches.size() > 1) {
			return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "'%s' matches %d themes; name one of them exactly." //$NON-NLS-1$
							.formatted(wanted, Integer.valueOf(matches.size())))
					.put("candidates", candidates(matches));
		}
		if (matches.isEmpty()) {
			return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "No registered theme matches '%s', so nothing was switched; handed an unregistered id the engine leaves the current theme up and logs a warning this answer would never show. Registered are:" //$NON-NLS-1$ //$NON-NLS-2$
							.formatted(wanted))
					.put("candidates", candidates(all));
		}
		ThemeRef target = matches.get(0);
		String previous = activeThemeId(engine);
		try {
			engine.getClass().getMethod("setTheme", String.class, boolean.class).invoke(engine, target.id(), //$NON-NLS-1$
					Boolean.valueOf(persist));
		} catch (InvocationTargetException e) {
			return new JsonObject().put("switched", Boolean.FALSE).put("theme", target.id()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "Switching the theme failed: " + e.getCause()); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new JsonObject().put("switched", Boolean.FALSE).put("theme", target.id()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "Switching the theme failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new JsonObject().put("switched", Boolean.TRUE) //$NON-NLS-1$
				.put("theme", target.id()) //$NON-NLS-1$
				.put("label", target.label()) //$NON-NLS-1$
				.put("previousThemeId", previous) //$NON-NLS-1$
				.put("persist", Boolean.valueOf(persist)) //$NON-NLS-1$
				.put("note", "Pass previousThemeId back to put the old theme as it was." //$NON-NLS-1$
						+ (persist ? "" : " Without persist the switch lasts until the IDE restarts.")
						+ " A theme change drops any snippet eclipse_apply_css put on top and takes its IEclipsePreferences overrides back with it.");
	}

	private static JsonArray candidates(List<ThemeRef> themes) {
		JsonArray candidates = new JsonArray();
		for (ThemeRef theme : themes.subList(0, Math.min(20, themes.size()))) {
			candidates.add(theme.describe());
		}
		return candidates;
	}

	/**
	 * Registers a theme for the rest of this session, from a stylesheet location.
	 * <p>
	 * Reached through {@code registerTheme(String, String, String)}, which the
	 * public interface declares; the four argument overload with the os version
	 * match exists only on the internal class and is not used.
	 */
	static JsonObject registerTheme(String id, String label, String stylesheetUri) {
		Object engine = themeEngine();
		if (engine == null) {
			return new JsonObject().put("registered", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The e4 CSS theme engine could not be reached."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (registeredThemes(engine).stream().anyMatch(theme -> theme.id().equals(id))) {
			return new JsonObject().put("registered", Boolean.FALSE).put("id", id) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "A theme with the id '%s' is already registered; switch to it with eclipse_set_theme." //$NON-NLS-1$ //$NON-NLS-2$
							.formatted(id));
		}
		try {
			engine.getClass().getMethod("registerTheme", String.class, String.class, String.class).invoke(engine, id, //$NON-NLS-1$
					label, stylesheetUri);
		} catch (InvocationTargetException e) {
			return new JsonObject().put("registered", Boolean.FALSE).put("id", id) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "Registering the theme failed: " + e.getCause()); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new JsonObject().put("registered", Boolean.FALSE).put("id", id) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "Registering the theme failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new JsonObject().put("registered", Boolean.TRUE) //$NON-NLS-1$
				.put("id", id) //$NON-NLS-1$
				.put("label", label) //$NON-NLS-1$
				.put("stylesheet", stylesheetUri) //$NON-NLS-1$
				.put("note", "The registration lives for this session only and is gone when the IDE restarts. Nothing was installed: the stylesheet is read from where it is every time the theme activates, so the file has to stay where it is for as long as the theme is used. Switch to it with eclipse_set_theme."); //$NON-NLS-1$
	}

	/**
	 * Takes an applied snippet back when the plug-in stops.
	 * <p>
	 * A snippet can leave the IDE unreadable, so the server going away must not be
	 * the moment that becomes permanent for the rest of the session.
	 */
	static void dropIfApplied() {
		if (snippet == null || !PlatformUI.isWorkbenchRunning()) {
			return;
		}
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.syncExec(() -> apply(null, true));
	}
}
