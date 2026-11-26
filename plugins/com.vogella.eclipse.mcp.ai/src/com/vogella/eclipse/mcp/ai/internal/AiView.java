package com.vogella.eclipse.mcp.ai.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IPerspectiveListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PerspectiveAdapter;
import org.eclipse.ui.part.ViewPart;

/**
 * The Claude Code sessions of this machine in one view: the terminal or transcript of the shown session, under a
 * drawer that lists every session by project and starts new ones.
 */
public class AiView extends ViewPart {

	public static final String ID = "com.vogella.eclipse.mcp.ai.view"; //$NON-NLS-1$
	private static final int AGE_REFRESH_MILLIS = 30_000;

	private Composite root;
	private Composite header;
	private Label title;
	private CLabel status;
	private ToolBar headerTools;
	private Composite content;
	private final StackLayout stack = new StackLayout();
	private Composite welcome;
	private TranscriptPane transcript;
	private final Map<TerminalSession, TerminalPane> terminals = new LinkedHashMap<>();
	private Composite drawer;
	private Text search;
	private TreeViewer tree;
	private boolean drawerOpen;
	private Listener outsideClick;
	private ResourceManager resources;

	/** The terminal or the external session shown, or {@code null} for the welcome page. */
	private Object current;
	private SessionWatcher watcher;
	private List<ClaudeSession> sessions = List.of();
	private List<SessionRow> rows = List.of();
	private final Map<Long, String> previousStatus = new HashMap<>();
	private final Map<Long, Optional<TmuxPane>> tmuxPanes = new HashMap<>();
	private final Runnable terminalsChanged = () -> asyncUpdate(null);
	private final IPerspectiveListener perspectiveListener = new PerspectiveAdapter() {
		@Override
		public void perspectiveActivated(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
			if (AiPerspective.ID.equals(perspective.getId())) {
				WindowChrome.enterAi(page.getWorkbenchWindow());
			} else {
				WindowChrome.leaveAi(page.getWorkbenchWindow());
			}
		}
	};
	private Color busyColor;
	private Color idleColor;
	private Color waitingColor;
	private boolean dark;
	/** Set while the tree is rebuilt, whose selection events must not open a session. */
	private boolean updating;

	@Override
	public void createPartControl(Composite parent) {
		resources = new LocalResourceManager(JFaceResources.getResources(), parent);
		createColors(parent.getDisplay());
		root = new Composite(parent, SWT.NONE);
		root.setLayout(new RootLayout());
		createHeader();
		createContent();
		createDrawer();

		TerminalSessions.addListener(terminalsChanged);
		watcher = new SessionWatcher(SessionDirectory.userDefault(), this::asyncUpdate);
		watcher.start();
		refreshAges();
		getSite().getWorkbenchWindow().addPerspectiveListener(perspectiveListener);
		IPerspectiveDescriptor perspective = getSite().getPage().getPerspective();
		if (perspective != null && AiPerspective.ID.equals(perspective.getId())) {
			WindowChrome.enterAi(getSite().getWorkbenchWindow());
		}
		showWelcome();
		setDrawerOpen(true);
	}

	private void createColors(Display display) {
		dark = isDark(display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB());
		busyColor = new Color(dark ? new RGB(240, 170, 80) : new RGB(190, 110, 0));
		idleColor = new Color(dark ? new RGB(110, 210, 130) : new RGB(30, 140, 60));
		waitingColor = new Color(dark ? new RGB(240, 110, 110) : new RGB(200, 40, 40));
	}

	private static boolean isDark(RGB background) {
		return background.red * 299 + background.green * 587 + background.blue * 114 < 128_000;
	}

	private Image image(String path) {
		return resources.create(Images.descriptor(path));
	}

	private void createHeader() {
		header = new Composite(root, SWT.NONE);
		GridLayout layout = new GridLayout(3, false);
		layout.marginHeight = 2;
		layout.marginWidth = 4;
		header.setLayout(layout);
		headerTools = new ToolBar(header, SWT.FLAT);
		ToolItem sidebar = new ToolItem(headerTools, SWT.PUSH);
		sidebar.setImage(image(Images.SIDEBAR));
		sidebar.setToolTipText(Messages.toggleSessions);
		sidebar.addListener(SWT.Selection, event -> setDrawerOpen(!drawerOpen));
		ToolItem find = new ToolItem(headerTools, SWT.PUSH);
		find.setImage(image(Images.SEARCH));
		find.setToolTipText(Messages.searchSessions);
		find.addListener(SWT.Selection, event -> {
			setDrawerOpen(true);
			search.setFocus();
		});
		title = new Label(header, SWT.NONE);
		title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
		title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		status = new CLabel(header, SWT.NONE);
		status.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
	}

	private void createContent() {
		content = new Composite(root, SWT.NONE);
		content.setLayout(stack);
		welcome = new Composite(content, SWT.NONE);
		welcome.setLayout(new GridLayout());
		Composite center = new Composite(welcome, SWT.NONE);
		center.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
		GridLayout centerLayout = new GridLayout();
		centerLayout.verticalSpacing = 10;
		center.setLayout(centerLayout);
		Label heading = new Label(center, SWT.CENTER);
		heading.setText(Messages.welcomeTitle);
		heading.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.HEADER_FONT));
		heading.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
		Label hint = new Label(center, SWT.CENTER | SWT.WRAP);
		hint.setText(Messages.welcomeHint);
		hint.setForeground(grey());
		GridData hintData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
		hintData.widthHint = 360;
		hint.setLayoutData(hintData);
		Button start = newSessionButton(center);
		start.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
	}

	private Button newSessionButton(Composite parent) {
		Button button = new Button(parent, SWT.PUSH);
		button.setText(Messages.newSession);
		button.setImage(image(Images.NEW_SESSION));
		button.setToolTipText(Messages.newSessionTooltip);
		button.addListener(SWT.Selection, event -> {
			Menu menu = projectsMenu(button);
			Rectangle bounds = button.getBounds();
			Point at = button.getParent().toDisplay(bounds.x, bounds.y + bounds.height);
			menu.setLocation(at);
			menu.setVisible(true);
		});
		return button;
	}

	private void createDrawer() {
		drawer = new Composite(root, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginWidth = 10;
		layout.marginHeight = 8;
		layout.verticalSpacing = 8;
		drawer.setLayout(layout);
		drawer.addPaintListener(event -> {
			Rectangle area = drawer.getClientArea();
			event.gc.setForeground(drawer.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
			event.gc.drawLine(area.width - 1, 0, area.width - 1, area.height);
		});

		Button start = newSessionButton(drawer);
		start.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		search = new Text(drawer, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
		search.setMessage(Messages.searchSessions);
		search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		search.addListener(SWT.Modify, event -> refreshTree());
		search.addListener(SWT.Traverse, event -> {
			if (event.detail == SWT.TRAVERSE_ESCAPE) {
				setDrawerOpen(false);
			}
		});

		Label sectionLabel = new Label(drawer, SWT.NONE);
		sectionLabel.setText(Messages.projectsSection);
		sectionLabel.setForeground(grey());

		tree = new TreeViewer(drawer, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		tree.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		tree.setContentProvider(new GroupedSessions());
		tree.setLabelProvider(new SessionLabels());
		tree.setAutoExpandLevel(AbstractTreeViewer.ALL_LEVELS);
		ColumnViewerToolTipSupport.enableFor(tree);
		tree.getTree().addListener(SWT.MouseUp, event -> {
			if (event.button == 1 && tree.getTree().getItem(new Point(event.x, event.y)) != null) {
				open(selected(), false);
			}
		});
		tree.getTree().addListener(SWT.DefaultSelection, event -> open(selected(), true));
		tree.getTree().addListener(SWT.Traverse, event -> {
			if (event.detail == SWT.TRAVERSE_ESCAPE) {
				setDrawerOpen(false);
			}
		});
		createContextMenu();

		outsideClick = event -> {
			if (drawerOpen && event.widget instanceof Control control && control.getShell() == root.getShell()
					&& !isInside(control, drawer) && !isInside(control, headerTools)) {
				setDrawerOpen(false);
			}
		};
		root.getDisplay().addFilter(SWT.MouseDown, outsideClick);
	}

	private static boolean isInside(Control control, Control ancestor) {
		for (Control c = control; c != null; c = c.getParent()) {
			if (c == ancestor) {
				return true;
			}
		}
		return false;
	}

	private void createContextMenu() {
		MenuManager menu = new MenuManager();
		menu.setRemoveAllWhenShown(true);
		menu.addMenuListener(manager -> {
			SessionRow row = selected();
			if (row == null) {
				return;
			}
			if (!row.owned() && row.session() != null && tmuxPane(row.session()) != null) {
				manager.add(action(Messages.attach, () -> attach(row)));
			}
			if (row.session() != null) {
				manager.add(action(Messages.showTranscript, () -> {
					showTranscript(row.session());
					setDrawerOpen(false);
				}));
			}
			if (row.owned()) {
				manager.add(new Separator());
				manager.add(action(Messages.closeTerminal, () -> closeTerminal(row.terminal())));
			}
		});
		tree.getControl().setMenu(menu.createContextMenu(tree.getControl()));
	}

	private static Action action(String text, Runnable run) {
		return new Action(text) {
			@Override
			public void run() {
				run.run();
			}
		};
	}

	private Menu projectsMenu(Control owner) {
		MenuManager manager = new MenuManager();
		for (IProject project : openProjects()) {
			String folder = project.getLocation().toOSString();
			manager.add(action(project.getName(), () -> start(folder)));
		}
		manager.add(new Separator());
		manager.add(action(Messages.chooseFolder, this::chooseFolderAndStart));
		Menu menu = manager.createContextMenu(owner);
		menu.addListener(SWT.Hide, event -> owner.getDisplay().asyncExec(manager::dispose));
		return menu;
	}

	private void setDrawerOpen(boolean open) {
		drawerOpen = open;
		drawer.setVisible(open);
		if (open) {
			drawer.moveAbove(null);
			root.layout(true);
		} else {
			focusCurrent();
		}
	}

	private SessionRow selected() {
		IStructuredSelection selection = tree.getStructuredSelection();
		return selection.getFirstElement() instanceof SessionRow row ? row : null;
	}

	private TmuxPane tmuxPane(ClaudeSession session) {
		return tmuxPanes.computeIfAbsent(session.pid(), pid -> Optional.ofNullable(TmuxPane.of(pid))).orElse(null);
	}

	private void asyncUpdate(List<ClaudeSession> latest) {
		Display display = root.getDisplay();
		if (display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (root.isDisposed()) {
				return;
			}
			if (latest != null) {
				sessions = latest;
			}
			update();
		});
	}

	private void update() {
		rows = SessionRow.of(sessions, TerminalSessions.all());
		tmuxPanes.keySet().retainAll(sessions.stream().map(ClaudeSession::pid).toList());
		for (SessionRow row : rows) {
			if (row.owned() && row.session() != null) {
				row.terminal().setTitle(row.session().displayName());
			}
		}
		notifyFinished();
		refreshTree();
		refreshHeader();
		if (current instanceof ClaudeSession shown) {
			sessions.stream().filter(session -> session.pid() == shown.pid() && !session.equals(shown)).findFirst()
					.ifPresent(this::showTranscript);
		}
	}

	private void refreshTree() {
		SessionRow selection = selected();
		updating = true;
		try {
			tree.setInput(rows);
			if (selection != null) {
				rows.stream().filter(row -> sameRow(row, selection)).findFirst()
						.ifPresent(row -> tree.setSelection(new StructuredSelection(row), true));
			}
		} finally {
			updating = false;
		}
	}

	private static boolean sameRow(SessionRow a, SessionRow b) {
		if (a.terminal() != null || b.terminal() != null) {
			return a.terminal() == b.terminal();
		}
		return a.session().pid() == b.session().pid();
	}

	private SessionRow rowOf(TerminalSession terminal) {
		return rows.stream().filter(row -> row.terminal() == terminal).findFirst().orElse(null);
	}

	private void refreshHeader() {
		String text;
		String badge = ""; //$NON-NLS-1$
		Color color = grey();
		if (current instanceof TerminalSession terminal) {
			SessionRow row = rowOf(terminal);
			text = row != null ? row.name() + "  ·  " + row.folder() : terminal.title(); //$NON-NLS-1$
			badge = row != null ? row.status() : terminal.ended() ? Messages.statusEnded : ""; //$NON-NLS-1$
			color = statusColor(badge);
		} else if (current instanceof ClaudeSession session) {
			text = session.displayName() + "  ·  " + session.folderName() + Messages.readOnlySuffix; //$NON-NLS-1$
			badge = session.status() != null ? session.status() : ""; //$NON-NLS-1$
		} else {
			text = Messages.welcomeHeader;
		}
		title.setText(text);
		status.setText(badge);
		status.setImage(badge.isEmpty() ? null : statusImage(badge));
		status.setForeground(color);
		header.layout(true);
		setTitleToolTip(text);
	}

	private Color statusColor(String value) {
		return switch (value) {
		case "busy", "shell" -> busyColor; //$NON-NLS-1$ //$NON-NLS-2$
		case "idle" -> idleColor; //$NON-NLS-1$
		case "waiting" -> waitingColor; //$NON-NLS-1$
		default -> grey();
		};
	}

	private Image statusImage(String value) {
		return image(switch (value) {
		case "busy", "shell", Messages.statusStarting -> Images.STATUS_BUSY; //$NON-NLS-1$ //$NON-NLS-2$
		case "idle" -> Images.STATUS_IDLE; //$NON-NLS-1$
		case "waiting" -> Images.STATUS_WAITING; //$NON-NLS-1$
		default -> Images.STATUS_ENDED;
		});
	}

	/** Pops up a notification for every own session that stopped working while its terminal is not shown. */
	private void notifyFinished() {
		Map<Long, String> now = new HashMap<>();
		for (SessionRow row : rows) {
			if (row.session() == null) {
				continue;
			}
			ClaudeSession session = row.session();
			now.put(session.pid(), session.status());
			String before = previousStatus.get(session.pid());
			boolean wasWorking = "busy".equals(before) || "shell".equals(before); //$NON-NLS-1$ //$NON-NLS-2$
			if (row.owned() && wasWorking && session.isWaitingForInput() && !isShowing(row.terminal())) {
				notifyFinished(row);
			}
		}
		previousStatus.clear();
		previousStatus.putAll(now);
	}

	private boolean isShowing(TerminalSession terminal) {
		return current == terminal && getSite().getPage().isPartVisible(this);
	}

	private void notifyFinished(SessionRow row) {
		TerminalSession terminal = row.terminal();
		NotificationPopup.forDisplay(root.getDisplay()).title(Messages.notificationTitle, true).content(parent -> {
			Composite body = new Composite(parent, SWT.NONE);
			body.setLayout(new GridLayout());
			Label text = new Label(body, SWT.WRAP);
			text.setText(Messages.notificationText(row.name(), row.folder()));
			Link show = new Link(body, SWT.NONE);
			show.setText(Messages.notificationShow);
			show.addListener(SWT.Selection, event -> {
				try {
					getSite().getPage().showView(ID);
				} catch (PartInitException e) {
					return;
				}
				showTerminal(terminal);
			});
			return body;
		}).open();
	}

	private void open(SessionRow row, boolean focus) {
		if (row == null || updating) {
			return;
		}
		if (row.owned()) {
			showTerminal(row.terminal());
		} else {
			TerminalSession attached = TerminalSessions.showing(row.session());
			if (attached != null) {
				showTerminal(attached);
			} else {
				showTranscript(row.session());
			}
		}
		setDrawerOpen(false);
		if (focus) {
			focusCurrent();
		}
	}

	private void showWelcome() {
		current = null;
		stack.topControl = welcome;
		content.layout();
		refreshHeader();
	}

	void showTerminal(TerminalSession terminal) {
		TerminalPane pane = terminals.computeIfAbsent(terminal,
				t -> new TerminalPane(content, t, this::refreshHeader));
		current = terminal;
		stack.topControl = pane.control();
		content.layout();
		refreshHeader();
		pane.setFocus();
	}

	private void showTranscript(ClaudeSession session) {
		if (transcript == null) {
			transcript = new TranscriptPane(content);
		}
		transcript.show(session);
		current = session;
		stack.topControl = transcript.control();
		content.layout();
		refreshHeader();
	}

	private void focusCurrent() {
		if (current instanceof TerminalSession terminal && terminals.get(terminal) != null) {
			terminals.get(terminal).setFocus();
		} else if (stack.topControl != null && !stack.topControl.isDisposed()) {
			stack.topControl.setFocus();
		}
	}

	private void closeTerminal(TerminalSession terminal) {
		TerminalPane pane = terminals.remove(terminal);
		if (pane != null) {
			pane.dispose();
		}
		TerminalSessions.remove(terminal);
		if (current == terminal) {
			showWelcome();
			setDrawerOpen(true);
		}
	}

	private void attach(SessionRow row) {
		TerminalSession existing = TerminalSessions.showing(row.session());
		if (existing != null) {
			showTerminal(existing);
			setDrawerOpen(false);
			return;
		}
		TmuxPane pane = tmuxPane(row.session());
		List<String> command = pane == null ? null : pane.attachCommand();
		if (command == null) {
			setContentDescription(Messages.tmuxPaneGone);
			return;
		}
		TerminalSession terminal = TerminalSession.attach(command, row.session(),
				Messages.attachTitle(pane.describe()));
		TerminalSessions.add(terminal);
		showTerminal(terminal);
		setDrawerOpen(false);
	}

	private void chooseFolderAndStart() {
		DirectoryDialog dialog = new DirectoryDialog(getSite().getShell());
		dialog.setMessage(Messages.chooseFolderMessage);
		dialog.setFilterPath(defaultFolder());
		String folder = dialog.open();
		if (folder != null) {
			start(folder);
		}
	}

	private String defaultFolder() {
		SessionRow row = selected();
		if (row != null && row.cwd() != null) {
			return row.cwd();
		}
		List<IProject> projects = openProjects();
		if (!projects.isEmpty()) {
			return projects.get(0).getLocation().toOSString();
		}
		return System.getProperty("user.home"); //$NON-NLS-1$
	}

	private static List<IProject> openProjects() {
		List<IProject> projects = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isOpen() && project.getLocation() != null) {
				projects.add(project);
			}
		}
		return projects;
	}

	void start(String folder) {
		String claude = Commands.findClaude();
		if (claude == null) {
			setContentDescription(Messages.claudeNotFound);
			return;
		}
		setContentDescription(""); //$NON-NLS-1$
		String name = new File(folder).getName();
		TerminalSession terminal = TerminalSession.claude(claude, folder, name.isEmpty() ? folder : name);
		TerminalSessions.add(terminal);
		showTerminal(terminal);
		setDrawerOpen(false);
	}

	private void refreshAges() {
		root.getDisplay().timerExec(AGE_REFRESH_MILLIS, () -> {
			if (!root.isDisposed()) {
				tree.refresh(true);
				refreshAges();
			}
		});
	}

	@Override
	public void setFocus() {
		if (drawerOpen) {
			tree.getControl().setFocus();
		} else {
			focusCurrent();
		}
	}

	@Override
	public void dispose() {
		TerminalSessions.removeListener(terminalsChanged);
		if (outsideClick != null && !root.getDisplay().isDisposed()) {
			root.getDisplay().removeFilter(SWT.MouseDown, outsideClick);
		}
		if (watcher != null) {
			watcher.stop();
		}
		terminals.keySet().forEach(TerminalSessions::remove);
		getSite().getWorkbenchWindow().removePerspectiveListener(perspectiveListener);
		WindowChrome.leaveAi(getSite().getWorkbenchWindow());
		super.dispose();
	}

	private Color grey() {
		return JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
	}

	/** Header on top, content below it, and the drawer over both on the left when it is open. */
	private final class RootLayout extends Layout {

		@Override
		protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
			return new Point(wHint == SWT.DEFAULT ? 400 : wHint, hHint == SWT.DEFAULT ? 300 : hHint);
		}

		@Override
		protected void layout(Composite composite, boolean flushCache) {
			Rectangle area = composite.getClientArea();
			int headerHeight = header.computeSize(area.width, SWT.DEFAULT, flushCache).y;
			header.setBounds(area.x, area.y, area.width, headerHeight);
			content.setBounds(area.x, area.y + headerHeight, area.width, Math.max(0, area.height - headerHeight));
			int width = Math.min(area.width, Math.max(260, Math.min(360, area.width * 2 / 5)));
			// below the header, whose sidebar button stays the toggle
			drawer.setBounds(area.x, area.y + headerHeight, width, Math.max(0, area.height - headerHeight));
		}
	}

	/** Groups the sessions by working directory, filtered by the search field. */
	private final class GroupedSessions implements ITreeContentProvider {

		@Override
		public Object[] getElements(Object input) {
			Map<String, Group> groups = new LinkedHashMap<>();
			String filter = search.getText().strip().toLowerCase(Locale.ROOT);
			for (SessionRow row : rows) {
				if (!filter.isEmpty() && !(row.name() + ' ' + row.cwd()).toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				String cwd = row.cwd() == null ? "" : row.cwd(); //$NON-NLS-1$
				groups.computeIfAbsent(cwd, key -> new Group(key, row.folder(), new ArrayList<>())).rows().add(row);
			}
			return groups.values().toArray();
		}

		@Override
		public Object[] getChildren(Object parent) {
			return parent instanceof Group group ? group.rows().toArray() : new Object[0];
		}

		@Override
		public Object getParent(Object element) {
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			return element instanceof Group;
		}
	}

	/** The sessions of one working directory; equal by directory so expansion survives a refresh. */
	private record Group(String cwd, String folder, List<SessionRow> rows) {

		@Override
		public boolean equals(Object other) {
			return other instanceof Group group && group.cwd.equals(cwd);
		}

		@Override
		public int hashCode() {
			return cwd.hashCode();
		}
	}

	private final class SessionLabels extends StyledCellLabelProvider {

		@Override
		public void update(ViewerCell cell) {
			if (cell.getElement() instanceof Group group) {
				StyledString text = new StyledString(group.folder());
				cell.setText(text.getString());
				cell.setImage(image(dark ? Images.FOLDER_DARK : Images.FOLDER));
				cell.setStyleRanges(text.getStyleRanges());
			} else {
				SessionRow row = (SessionRow) cell.getElement();
				String name = row.name();
				String age = row.session() == null ? "" //$NON-NLS-1$
						: "   " + SessionRow.age(row.idleMillis(System.currentTimeMillis())); //$NON-NLS-1$
				String text = name + age;
				cell.setText(text);
				cell.setImage(statusImage(row.status()));
				List<StyleRange> styles = new ArrayList<>();
				if (!row.owned()) {
					styles.add(new StyleRange(0, name.length(), grey(), null));
				}
				if (!age.isEmpty()) {
					styles.add(new StyleRange(name.length(), age.length(), grey(), null));
				}
				cell.setStyleRanges(styles.toArray(StyleRange[]::new));
			}
			super.update(cell);
		}

		@Override
		public String getToolTipText(Object element) {
			if (element instanceof Group group) {
				return group.cwd();
			}
			SessionRow row = (SessionRow) element;
			String tip = row.status() + " · " + row.cwd(); //$NON-NLS-1$
			if (row.session() != null && row.session().version() != null) {
				tip += "\nClaude Code " + row.session().version(); //$NON-NLS-1$
			}
			return row.owned() ? tip : tip + '\n' + Messages.externalTooltip;
		}
	}
}
