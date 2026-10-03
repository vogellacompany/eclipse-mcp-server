package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Annotations that mean something outside this compilation unit reads or writes
 * the member, which is exactly what a reference count cannot see.
 * <p>
 * A field carrying one of these is written by a framework, so JDT reports zero references and every dead code
 * heuristic agrees it can go.
 */
final class InjectionAnnotations {

	/**
	 * Matched on the simple name, since that is what source carries once imported; a false positive costs a sentence.
	 */
	private static final Set<String> SIMPLE_NAMES = Set.of("Reference", //$NON-NLS-1$
			"Activate", "Deactivate", "Modified", // OSGi declarative services //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"Inject", "PostConstruct", "PreDestroy", // jakarta and javax inject //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"Execute", "CanExecute", "Optional", // e4 dependency injection //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"Preference", "EventTopic", "Service", "UIEventTopic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"Autowired", "Value", "Bean", // Spring //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"Entity", "Column", "Id", "XmlElement", "XmlAttribute"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	private InjectionAnnotations() {
	}

	/** The injection annotations on a member, simple names, empty when there are none. */
	static List<String> on(IJavaElement element) {
		if (!(element instanceof IAnnotatable annotatable)) {
			return List.of();
		}
		try {
			return Arrays.stream(annotatable.getAnnotations()).map(IAnnotation::getElementName)
					.map(InjectionAnnotations::simpleName).filter(SIMPLE_NAMES::contains).distinct().toList();
		} catch (JavaModelException e) {
			return List.of();
		}
	}

	private static String simpleName(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}

	/** What to tell a caller who is about to read a zero reference count as "dead". */
	static String warning(List<String> annotations) {
		return "This member declares %s, which means a framework reads or writes it from outside any compilation unit. A reference count of zero therefore does NOT mean it is unused: JDT cannot see the injection, and neither can a text search. Check what wires it before removing it." //$NON-NLS-1$
				.formatted(annotations.stream().map(name -> "@" + name).collect(Collectors.joining(", "))); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
