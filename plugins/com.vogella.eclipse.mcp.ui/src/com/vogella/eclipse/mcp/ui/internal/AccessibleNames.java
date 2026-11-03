package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.util.List;

import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.Accessible;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Widget;

/**
 * What a custom-painted control tells a screen reader, often the only text it has: the selected entry of
 * a list, or the current cell of a grid.
 */
public final class AccessibleNames {

	private AccessibleNames() {
	}

	/** The name its accessibility listeners answer, or {@code null}. */
	public static String of(Widget widget) {
		if (!(widget instanceof Control control)) {
			return null;
		}
		try {
			// the field, since getAccessible would create a native object for every control asked
			Field field = Control.class.getDeclaredField("accessible"); //$NON-NLS-1$
			field.setAccessible(true);
			if (!(field.get(control) instanceof Accessible accessible)) {
				return null;
			}
			Field listeners = Accessible.class.getDeclaredField("accessibleListeners"); //$NON-NLS-1$
			listeners.setAccessible(true);
			if (!(listeners.get(accessible) instanceof List<?> list)) {
				return null;
			}
			AccessibleEvent event = new AccessibleEvent(accessible);
			event.childID = ACC.CHILDID_SELF;
			for (Object listener : list) {
				((AccessibleListener) listener).getName(event);
			}
			return event.result == null || event.result.isBlank() ? null : event.result;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}
}
