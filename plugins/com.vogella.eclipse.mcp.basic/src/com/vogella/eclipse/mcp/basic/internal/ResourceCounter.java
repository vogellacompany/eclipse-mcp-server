package com.vogella.eclipse.mcp.basic.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Counts the files a refresh or a build actually touched.
 * <p>
 * Without this a build that did nothing is indistinguishable from one that
 * rebuilt a whole branch switch: both report a state and a duration, and a fast
 * duration is exactly what "nothing happened" looks like. A caller that then
 * reports "no new errors" is making a claim the answer does not support.
 */
final class ResourceCounter implements IResourceChangeListener {

	private final AtomicInteger added = new AtomicInteger();

	private final AtomicInteger changed = new AtomicInteger();

	private final AtomicInteger removed = new AtomicInteger();

	private final Set<String> projects = Collections.synchronizedSet(new LinkedHashSet<>());

	private final int eventMask;

	private ResourceCounter(int eventMask) {
		this.eventMask = eventMask;
	}

	/** Counts what the workspace picked up from disk. */
	static ResourceCounter forRefresh() {
		return new ResourceCounter(IResourceChangeEvent.POST_CHANGE);
	}

	/** Counts what the builders produced, which is mostly build output. */
	static ResourceCounter forBuild() {
		return new ResourceCounter(IResourceChangeEvent.POST_BUILD);
	}

	void start() {
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this, eventMask);
	}

	void stop() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
	}

	int files() {
		return added.get() + changed.get() + removed.get();
	}

	int added() {
		return added.get();
	}

	int changed() {
		return changed.get();
	}

	int removed() {
		return removed.get();
	}

	Set<String> projects() {
		synchronized (projects) {
			return Set.copyOf(projects);
		}
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		if (delta == null) {
			return;
		}
		try {
			delta.accept(this::visit);
		} catch (org.eclipse.core.runtime.CoreException e) {
			// counting is a diagnostic aid, not the result; a build that ran still ran
		}
	}

	private boolean visit(IResourceDelta delta) {
		IResource resource = delta.getResource();
		if (resource.getType() != IResource.FILE) {
			return true;
		}
		// a marker-only or sync-info-only delta means the file itself did not change,
		// and counting it would make a marker update look like a recompilation
		if (delta.getKind() == IResourceDelta.CHANGED && (delta.getFlags() & IResourceDelta.CONTENT) == 0
				&& (delta.getFlags() & IResourceDelta.REPLACED) == 0) {
			return true;
		}
		switch (delta.getKind()) {
		case IResourceDelta.ADDED -> added.incrementAndGet();
		case IResourceDelta.REMOVED -> removed.incrementAndGet();
		case IResourceDelta.CHANGED -> changed.incrementAndGet();
		default -> {
			return true;
		}
		}
		if (resource.getProject() != null) {
			projects.add(resource.getProject().getName());
		}
		return true;
	}
}
