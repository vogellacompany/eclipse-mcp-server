package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * What AppKit itself reports about activation and the first responder, which can
 * differ from SWT's view of them. SWT's Cocoa internals exist on one window system
 * only and are reached by name. Call on the UI thread.
 */
public final class NativeAppKit {

	private static final String COCOA = "org.eclipse.swt.internal.cocoa."; //$NON-NLS-1$

	private NativeAppKit() {
	}

	/** Whether AppKit reports this application active, or null off Cocoa or when it cannot be read. */
	public static Boolean active() {
		if (!"cocoa".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		try {
			return isActive(application());
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * The active state, key window and first responder, or null off Cocoa. On Cocoa
	 * it never returns null and reports a failure to read SWT's internals under
	 * {@code unreadable}.
	 */
	public static JsonObject describe(Display display, Control focus) {
		if (!"cocoa".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		JsonObject json = new JsonObject().put("active", null).put("keyWindow", null).put("firstResponder", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		try {
			Object application = application();
			json.put("active", isActive(application)); //$NON-NLS-1$
			Object window = application.getClass().getMethod("keyWindow").invoke(application); //$NON-NLS-1$
			if (window != null && idOf(window) != 0) {
				json.put("keyWindow", display.findWidget(idOf(window)) instanceof Shell shell ? shell.getText() : null) //$NON-NLS-1$
						.put("responderWindow", "keyWindow"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				// An inactive application has no key window, but its windows keep their first responder.
				window = focus != null ? windowOf(focus.getShell()) : null;
				if (window == null || idOf(window) == 0) {
					return json;
				}
				json.put("responderWindow", "focusShell"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Object responder = window.getClass().getMethod("firstResponder").invoke(window); //$NON-NLS-1$
			return responder == null || idOf(responder) == 0 ? json
					: json.put("firstResponder", responder(display, focus, responder)); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return json.put("unreadable", e.toString()); //$NON-NLS-1$
		}
	}

	private static JsonObject responder(Display display, Control focus, Object responder)
			throws ReflectiveOperationException {
		Object className = responder.getClass().getMethod("className").invoke(responder); //$NON-NLS-1$
		Widget widget = owner(display, responder);
		JsonObject json = new JsonObject().put("nativeClass", stringOf(className)) //$NON-NLS-1$
				.put("widgetClass", widget == null ? null : widget.getClass().getName()); //$NON-NLS-1$
		if (widget instanceof Control control && !(control instanceof Shell)) {
			json.put("path", WidgetTools.pathOf(control.getShell(), control)); //$NON-NLS-1$
		}
		return json.put("isFocusControl", widget == null ? null : Boolean.valueOf(widget == focus)); //$NON-NLS-1$
	}

	/**
	 * The widget a responder belongs to. A Text edits through the window's shared
	 * field editor and a Browser through WebKit's views, which no widget owns, so
	 * this walks up the superviews to the first control, the way
	 * {@code Display._getFocusControl} does.
	 */
	private static Widget owner(Display display, Object responder) throws ReflectiveOperationException {
		ClassLoader loader = Shell.class.getClassLoader();
		long superview = Class.forName(COCOA + "OS", true, loader).getField("sel_superview").getLong(null); //$NON-NLS-1$ //$NON-NLS-2$
		long id = idOf(responder);
		if (!(Boolean) responder.getClass().getMethod("respondsToSelector", long.class).invoke(responder, //$NON-NLS-1$
				Long.valueOf(superview))) {
			return display.findWidget(id);
		}
		Class<?> viewClass = Class.forName(COCOA + "NSView", true, loader); //$NON-NLS-1$
		Object view = viewClass.getConstructor(long.class).newInstance(Long.valueOf(id));
		while (view != null) {
			Widget widget = display.findWidget(idOf(view));
			if (widget instanceof Control) {
				return widget;
			}
			view = viewClass.getMethod("superview").invoke(view); //$NON-NLS-1$
		}
		return null;
	}

	private static Object windowOf(Shell shell) throws ReflectiveOperationException {
		Object view = Control.class.getField("view").get(shell); //$NON-NLS-1$
		return view == null ? null : view.getClass().getMethod("window").invoke(view); //$NON-NLS-1$
	}

	private static Object application() throws ReflectiveOperationException {
		return Class.forName(COCOA + "NSApplication", true, Shell.class.getClassLoader()) //$NON-NLS-1$
				.getMethod("sharedApplication").invoke(null); //$NON-NLS-1$
	}

	private static Boolean isActive(Object application) throws ReflectiveOperationException {
		return (Boolean) application.getClass().getMethod("isActive").invoke(application); //$NON-NLS-1$
	}

	private static long idOf(Object object) throws ReflectiveOperationException {
		Field id = object.getClass().getField("id"); //$NON-NLS-1$
		return id.getLong(object);
	}

	private static String stringOf(Object nsString) throws ReflectiveOperationException {
		return (String) nsString.getClass().getMethod("getString").invoke(nsString); //$NON-NLS-1$
	}
}
