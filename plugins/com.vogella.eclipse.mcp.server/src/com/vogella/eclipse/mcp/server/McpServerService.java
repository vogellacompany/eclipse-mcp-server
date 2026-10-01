package com.vogella.eclipse.mcp.server;

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

	/** How long a request waits for the tools to load, below the 30 seconds clients give a connection. */
	private static final long READY_WAIT_MILLIS = 25_000;

	private static final McpServerService INSTANCE = new McpServerService();

	private Server jetty;

	private McpSyncServer mcpServer;

	private HttpServletStreamableServerTransportProvider transport;

	private ReadyGate readyGate;

	private ExecutorService toolExecutor;

	private McpEndpoint endpoint;

	private int runningPort = -1;

	private String lastError;

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

	/**
	 * The persisted bearer token, or {@code null} when none has been generated yet.
	 * <p>
	 * Available whether or not the server is running: the token lives in the user
	 * area, so it is a property of this user rather than of the process or of the
	 * workspace, and a client can be configured with it before the server is ever
	 * started.
	 */
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

		try {
			jetty = createJetty(port, token);
			jetty.start();
		} catch (Exception e) {
			stopQuietly();
			lastError = "Could not listen on %s:%d. %s".formatted(LOOPBACK, port, //$NON-NLS-1$
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			throw new McpServerException("Could not start the MCP server on %s:%d".formatted(LOOPBACK, port), e); //$NON-NLS-1$
		}

		// loading the tools takes seconds on a fresh start, and the socket is already bound by now so
		// that a client reconnecting after a restart waits in the ready gate instead of being refused
		List<SyncToolSpecification> specifications;
		try {
			specifications = McpToolRegistry.getInstance().getTools().stream()
					.map(tool -> McpToolAdapter.toSpecification(tool, jsonMapper, toolExecutor)).toList();
			mcpServer = McpServer.sync(transport).serverInfo("eclipse-mcp", version()) //$NON-NLS-1$
					.instructions("Access to the Java model, the problem markers, the Error Log, the preferences, the workbench and the editor context of a running Eclipse IDE, plus its files and text search. Most tools only read, and the ones that change something say so in their own description in capitals. Those are: renaming and deleting Java elements through the refactoring engine, formatting and organizing imports, running builds and tests, opening and closing projects, writing preferences, setting plug-in execution environments, updating the installation, clearing the Error Log, and showing, hiding or restarting the IDE itself. Every one of those is a dry run by default where a dry run makes sense. Writing is possible but narrow where it matters: eclipse_write_file writes one workspace file, refuses to overwrite unless asked, and puts the previous content into local history, which is why it is the one changing tool that does not default to a dry run. There is no terminal, though eclipse_run_command runs a command in a directory the user has named and eclipse_get_command_output reads what it printed. The debugger is controllable: breakpoints, launching, stepping, reading frames and evaluating an expression in a suspended frame. Recording the JVM with Java Flight Recorder is available too, which is what answers where the memory goes. When several clients are connected, pass ids explicitly rather than relying on a tool's most-recent default. Sessions are counted, not clients: a session is forgotten when it ends with an HTTP DELETE or after 60 seconds without a request, so a client that opens a new session per call and never ends one will look like many clients and will be told to pass ids it should not need to.") //$NON-NLS-1$
					.capabilities(ServerCapabilities.builder().tools(false).build()).jsonMapper(jsonMapper)
					.jsonSchemaValidator(new BundleJsonSchemaValidator()).tools(specifications).build();
		} catch (RuntimeException e) {
			stopQuietly();
			lastError = "Could not load the tools. %s".formatted( //$NON-NLS-1$
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			throw new McpServerException("Could not load the MCP tools", e); //$NON-NLS-1$
		}
		readyGate.open();
		lastError = null;

		runningPort = port;
		endpoint = new McpEndpoint("http://%s:%d%s".formatted(LOOPBACK, port, ENDPOINT_PATH), token); //$NON-NLS-1$
		ClientSessions.setProvider(ActiveSessions::count);
		TracePages.setPublisher((title, html) -> "http://%s:%d%s/%s".formatted(LOOPBACK, Integer.valueOf(port), //$NON-NLS-1$
				TRACE_PATH, TracePageStore.add(title, html)));
		EndpointFile.write(endpoint);
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
		// deliberately outside that mapping: a browser cannot put the bearer token on
		// a plain navigation, so a trace page is guarded by 128 random bits in its own
		// URL instead. The connector is loopback only, so the pair is a capability URL
		// that never leaves this machine
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
