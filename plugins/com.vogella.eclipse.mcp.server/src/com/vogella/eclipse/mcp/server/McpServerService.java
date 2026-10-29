package com.vogella.eclipse.mcp.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.server.internal.ActiveSessions;
import com.vogella.eclipse.mcp.server.internal.BearerTokenFilter;
import com.vogella.eclipse.mcp.server.internal.JsonRpcErrorFilter;
import com.vogella.eclipse.mcp.server.internal.BundleJsonSchemaValidator;
import com.vogella.eclipse.mcp.server.internal.EndpointFile;
import com.vogella.eclipse.mcp.server.internal.McpToolAdapter;
import com.vogella.eclipse.mcp.server.internal.ReadyGate;
import com.vogella.eclipse.mcp.server.internal.TracePageStore;
import com.vogella.eclipse.mcp.server.internal.TraceServlet;
import com.vogella.eclipse.mcp.server.internal.TokenStore;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import jakarta.servlet.DispatcherType;

/**
 * The embedded MCP server: an HTTP endpoint on the loopback interface that exposes every
 * tool of the {@link McpToolRegistry} over the Streamable HTTP transport.
 */
public final class McpServerService {

	private static final String ENDPOINT_PATH = "/mcp"; //$NON-NLS-1$

	private static final String TRACE_PATH = "/trace"; //$NON-NLS-1$

	private static final String LOOPBACK = "127.0.0.1"; //$NON-NLS-1$

	/** How long a request waits for the tools to load, kept below the 30 seconds Claude Code gives a connection. */
	private static final long READY_WAIT_MILLIS = 25_000;

	private static final int PORT_SEARCH_RANGE = 20;

	/**
	 * How long a start waits for its port to come free. A runtime IDE launched right after
	 * another one was killed can find the old process still closing its socket.
	 */
	private static final long PORT_WAIT_MILLIS = 10_000;

	private static final McpServerService INSTANCE = new McpServerService();

	private Server jetty;

	private McpSyncServer mcpServer;

	private HttpServletStreamableServerTransportProvider transport;

	private ReadyGate readyGate;

	private ExecutorService toolExecutor;

	private McpEndpoint endpoint;

	private int runningPort = -1;

	private String lastError;
	private boolean lastErrorIsPort;

	private McpServerService() {
	}

	public static McpServerService getInstance() {
		return INSTANCE;
	}

	public synchronized boolean isRunning() {
		return jetty != null && jetty.isRunning();
	}

	/** The port the server is listening on, or {@code -1} while it is stopped. */
	public synchronized int getPort() {
		return runningPort;
	}

	/** The endpoint clients have to talk to, or {@code null} while the server is stopped. */
	public synchronized McpEndpoint getEndpoint() {
		return endpoint;
	}

	/** The discovery file holding the URL and the token, whether or not the server is running. */
	public static Path getEndpointFile() {
		return EndpointFile.location();
	}

	/** The persisted bearer token, available whether or not the server is running. */
	public static String getToken() {
		return TokenStore.get();
	}

	/**
	 * The message of the last failed {@link #start()}, or {@code null} when the server
	 * started or was never asked to. Typically a port that is already in use.
	 */
	public synchronized String getLastError() {
		return lastError;
	}

	/** Whether {@link #getLastError()} is a failure to bind the port, as opposed to a later startup failure. */
	public synchronized boolean isLastErrorPortFailure() {
		return lastError != null && lastErrorIsPort;
	}

	/**
	 * The first port after {@code taken} that can be bound on the loopback interface, or
	 * {@code -1} when none of the next {@value #PORT_SEARCH_RANGE} is free. Only a
	 * suggestion for the user; the server never moves on its own.
	 */
	public static int findFreePort(int taken) {
		for (int port = taken + 1; port <= Math.min(taken + PORT_SEARCH_RANGE, 65535); port++) {
			try (ServerSocket probe = new ServerSocket()) {
				probe.setReuseAddress(false);
				probe.bind(new InetSocketAddress(LOOPBACK, port));
				return port;
			} catch (IOException e) {
				// taken as well, try the next one
			}
		}
		return -1;
	}

	/** Waits until the port can be bound, and answers whether it could within {@value #PORT_WAIT_MILLIS} ms. */
	private static boolean awaitPort(int port) {
		long deadline = System.nanoTime() + PORT_WAIT_MILLIS * 1_000_000;
		while (true) {
			// the same reuse setting as the connector, so a socket in TIME_WAIT does not count as taken
			try (ServerSocket probe = new ServerSocket()) {
				probe.setReuseAddress(true);
				probe.bind(new InetSocketAddress(LOOPBACK, port));
				return true;
			} catch (IOException e) {
				if (System.nanoTime() > deadline) {
					return false;
				}
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	private static boolean isBindFailure(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof java.net.BindException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Replaces the bearer token and restarts the server when it is running, so that the
	 * new token takes effect. Every configured client has to be updated afterwards.
	 */
	public synchronized void regenerateToken() throws McpServerException {
		TokenStore.regenerate();
		if (isRunning()) {
			stop();
			start();
		}
	}

	/**
	 * Starts the server on the configured port. Does nothing when it is already running.
	 * Never call this on the UI thread, starting Jetty takes a moment.
	 */
	public synchronized void start() throws McpServerException {
		if (isRunning()) {
			return;
		}
		int port = McpPreferences.getPort();
		String token = TokenStore.get();
		McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
		toolExecutor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "MCP tool call"); //$NON-NLS-1$
			thread.setDaemon(true);
			return thread;
		});

		transport = HttpServletStreamableServerTransportProvider.builder().jsonMapper(jsonMapper)
				.mcpEndpoint(ENDPOINT_PATH)
				.securityValidator(DefaultServerTransportSecurityValidator.builder().allowedHost(LOOPBACK + ":*") //$NON-NLS-1$
						.allowedHost("localhost:*").build()) //$NON-NLS-1$
				.build();
		readyGate = new ReadyGate(READY_WAIT_MILLIS);

		if (!awaitPort(port)) {
			ILog.get().warn("Port %d on %s is still taken after %d seconds, trying to listen on it anyway" //$NON-NLS-1$
					.formatted(Integer.valueOf(port), LOOPBACK, Long.valueOf(PORT_WAIT_MILLIS / 1000)));
		}
		try {
			jetty = createJetty(port, token);
			jetty.start();
		} catch (Exception e) {
			stopQuietly();
			lastErrorIsPort = isBindFailure(e);
			lastError = "Could not listen on %s:%d. %s".formatted(LOOPBACK, port, //$NON-NLS-1$
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			EndpointFile.markFailed(lastError);
			throw new McpServerException("Could not start the MCP server on %s:%d".formatted(LOOPBACK, port), e); //$NON-NLS-1$
		}

		// loading the tools takes seconds on a fresh start, and the socket is already bound by now so
		// that a client reconnecting after a restart waits in the ready gate instead of being refused
		List<SyncToolSpecification> specifications;
		try {
			specifications = McpToolRegistry.getInstance().getTools().stream()
					.map(tool -> McpToolAdapter.toSpecification(tool, jsonMapper, toolExecutor)).toList();
			mcpServer = McpServer.sync(transport).serverInfo("eclipse-mcp", version()) //$NON-NLS-1$
					.instructions("A running Eclipse IDE: the Java model, problem markers, builds, tests, the debugger, the Error Log, preferences, files, and the live SWT workbench. " //$NON-NLS-1$
							+ "Most tools only read; a tool that changes something says so in capitals in its own description and defaults to a dry run where one makes sense. " //$NON-NLS-1$
							+ "To test the UI or an SWT change: eclipse_list_ui_targets names shells and parts, eclipse_get_widget_tree gives each widget's path, bounds and with includeMetrics what it computes for itself; " //$NON-NLS-1$
							+ "drive it with eclipse_press_widget, eclipse_set_widget_text, eclipse_click, eclipse_press_key and eclipse_type_text; wait on a condition with eclipse_wait_for rather than a pause; " //$NON-NLS-1$
							+ "capture with eclipse_screenshot, settle and settlePixels for stable pixels. eclipse_run_script runs many steps with expect assertions in one call. " //$NON-NLS-1$
							+ "eclipse_mark_log then eclipse_get_log_entries shows what an action logged, UI freezes included. " //$NON-NLS-1$
							+ "To run changed platform code, eclipse_substitute_bundle swaps in a workspace bundle at the next eclipse_restart, and eclipse_hot_code_replace redefines classes in place. " //$NON-NLS-1$
							+ "Builds, test runs and provisioning return a handle to poll. With several clients connected, pass ids explicitly instead of relying on a most-recent default; a session ends with an HTTP DELETE or after 60 seconds without a request.") //$NON-NLS-1$
					.capabilities(ServerCapabilities.builder().tools(false).build()).jsonMapper(jsonMapper)
					.jsonSchemaValidator(new BundleJsonSchemaValidator()).tools(specifications).build();
			runningPort = port;
			endpoint = new McpEndpoint("http://%s:%d%s".formatted(LOOPBACK, port, ENDPOINT_PATH), token); //$NON-NLS-1$
			ClientSessions.setProvider(ActiveSessions::count);
			TracePages.setPublisher((title, html) -> "http://%s:%d%s/%s".formatted(LOOPBACK, Integer.valueOf(port), //$NON-NLS-1$
					TRACE_PATH, TracePageStore.add(title, html)));
			EndpointFile.write(endpoint);
		} catch (RuntimeException | Error e) {
			stopQuietly();
			lastErrorIsPort = false;
			lastError = "Could not load the tools or publish the endpoint. %s".formatted( //$NON-NLS-1$
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			EndpointFile.markFailed(lastError);
			throw new McpServerException("Could not load the MCP tools", e); //$NON-NLS-1$
		}
		lastError = null;
		readyGate.open();
		ILog.get().info("MCP server listening on %s with %d tool(s)".formatted(endpoint.url(), specifications.size())); //$NON-NLS-1$
	}

	/** Stops the server and removes the discovery file. Does nothing when it is not running. */
	public synchronized void stop() {
		if (jetty == null) {
			return;
		}
		stopQuietly();
		ILog.get().info("MCP server stopped"); //$NON-NLS-1$
	}

	/** Read from the bundle, so that the version a client sees cannot drift from the build. */
	private static String version() {
		Bundle bundle = FrameworkUtil.getBundle(McpServerService.class);
		if (bundle == null) {
			return "0.0.0"; //$NON-NLS-1$
		}
		Version version = bundle.getVersion();
		return "%d.%d.%d".formatted(version.getMajor(), version.getMinor(), version.getMicro()); //$NON-NLS-1$
	}

	private Server createJetty(int port, String token) {
		QueuedThreadPool threadPool = new QueuedThreadPool(16, 2);
		threadPool.setName("mcp-jetty"); //$NON-NLS-1$
		threadPool.setDaemon(true);
		Server server = new Server(threadPool);

		ServerConnector connector = new ServerConnector(server);
		// binds to 127.0.0.1 only, so the socket is not reachable from another machine
		connector.setHost(LOOPBACK);
		connector.setPort(port);
		// Jetty's default, stated because a restart right after a stop relies on it: the old
		// connections may still be in TIME_WAIT, which must not keep the port from binding
		connector.setReuseAddress(true);
		server.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler("/"); //$NON-NLS-1$
		ServletHolder servlet = new ServletHolder(transport);
		servlet.setAsyncSupported(true);
		context.addServlet(servlet, ENDPOINT_PATH + "/*"); //$NON-NLS-1$
		FilterHolder filter = new FilterHolder(new BearerTokenFilter(token));
		filter.setAsyncSupported(true);
		context.addFilter(filter, ENDPOINT_PATH + "/*", EnumSet.of(DispatcherType.REQUEST)); //$NON-NLS-1$
		FilterHolder gate = new FilterHolder(readyGate);
		gate.setAsyncSupported(true);
		context.addFilter(gate, ENDPOINT_PATH + "/*", EnumSet.of(DispatcherType.REQUEST)); //$NON-NLS-1$
		FilterHolder errors = new FilterHolder(new JsonRpcErrorFilter());
		errors.setAsyncSupported(true);
		context.addFilter(errors, ENDPOINT_PATH + "/*", EnumSet.of(DispatcherType.REQUEST)); //$NON-NLS-1$
		// outside that mapping: a browser cannot send the bearer token, so the trace page
		// is guarded by 128 random bits in its URL instead
		context.addServlet(new ServletHolder(new TraceServlet()), TRACE_PATH + "/*"); //$NON-NLS-1$
		server.setHandler(context);
		return server;
	}

	private void stopQuietly() {
		if (readyGate != null) {
			readyGate.close();
		}
		ClientSessions.setProvider(null);
		TracePages.setPublisher(null);
		TracePageStore.clear();
		ActiveSessions.clear();
		EndpointFile.markStopped();
		endpoint = null;
		runningPort = -1;
		if (mcpServer != null) {
			try {
				mcpServer.closeGracefully();
			} catch (RuntimeException e) {
				ILog.get().warn("Could not close the MCP server gracefully", e); //$NON-NLS-1$
			}
			mcpServer = null;
		}
		if (jetty != null) {
			try {
				jetty.stop();
			} catch (Exception e) {
				ILog.get().error("Could not stop the embedded Jetty server", e); //$NON-NLS-1$
			}
			jetty = null;
		}
		transport = null;
		readyGate = null;
		if (toolExecutor != null) {
			toolExecutor.shutdownNow();
			toolExecutor = null;
		}
	}
}
