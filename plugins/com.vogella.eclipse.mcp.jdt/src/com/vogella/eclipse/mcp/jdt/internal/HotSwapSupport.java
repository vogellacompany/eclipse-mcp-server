package com.vogella.eclipse.mcp.jdt.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.McpToolException;

/**
 * Obtains an {@link Instrumentation} for this JVM, loading the agent through a
 * helper process the first time it is asked.
 */
final class HotSwapSupport {

	static final String AGENT_CLASS = "com.vogella.eclipse.mcp.jdt.internal.hotswap.HotSwapAgent"; //$NON-NLS-1$

	static final String ATTACHER_CLASS = "com.vogella.eclipse.mcp.jdt.internal.hotswap.HotSwapAttacher"; //$NON-NLS-1$

	private static final String JAR_NAME = "hotswap-agent.jar"; //$NON-NLS-1$

	private static final String DISABLED_FLAG = "-XX:-EnableDynamicAgentLoading"; //$NON-NLS-1$

	/** How the instrumentation was obtained, for the answer. */
	record Attachment(Instrumentation instrumentation, String how, long helperMillis, Path agentJar) {
	}

	private HotSwapSupport() {
	}

	/** The agent's instrumentation when it is already loaded, else {@code null}. */
	static Instrumentation existing() {
		try {
			Class<?> agent = ClassLoader.getSystemClassLoader().loadClass(AGENT_CLASS);
			return (Instrumentation) agent.getMethod("instrumentation").invoke(null); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Why an agent cannot be loaded into this JVM, or {@code null} when it can. */
	static String unsupportedReason(String javaExecutable) {
		if (ModuleLayer.boot().findModule("java.instrument").isEmpty()) { //$NON-NLS-1$
			return "This IDE runs on a JVM without the java.instrument module, so no agent can be loaded into it and classes cannot be redefined."; //$NON-NLS-1$
		}
		if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains(DISABLED_FLAG)) {
			return "This IDE was started with " + DISABLED_FLAG //$NON-NLS-1$
					+ ", which forbids loading an agent into it; remove that option from eclipse.ini and restart."; //$NON-NLS-1$
		}
		if (javaExecutable == null && ModuleLayer.boot().findModule("jdk.attach").isEmpty()) { //$NON-NLS-1$
			return "The JVM this IDE runs on has no jdk.attach module, so it cannot serve as the attach helper; pass 'java' pointing at a JDK's java executable."; //$NON-NLS-1$
		}
		return null;
	}

	static synchronized Attachment attach(String javaExecutable, int timeoutSeconds) throws McpToolException {
		Instrumentation loaded = existing();
		if (loaded != null) {
			return new Attachment(loaded, "alreadyLoaded", 0, null); //$NON-NLS-1$
		}
		String refusal = unsupportedReason(javaExecutable);
		if (refusal != null) {
			throw new McpToolException(refusal);
		}
		Path java = javaExecutable == null ? defaultJava() : Path.of(javaExecutable);
		if (!Files.isRegularFile(java)) {
			throw new McpToolException("There is no java executable at " + java); //$NON-NLS-1$
		}
		Path jar;
		try {
			jar = writeAgentJar();
		} catch (IOException e) {
			throw new McpToolException("Could not write the agent jar", e); //$NON-NLS-1$
		}
		long start = System.nanoTime();
		String output = runHelper(java, jar, timeoutSeconds);
		long millis = (System.nanoTime() - start) / 1_000_000;
		loaded = existing();
		if (loaded == null) {
			throw new McpToolException("The attach helper ran but no agent is loaded. Helper output: " + output); //$NON-NLS-1$
		}
		if (!loaded.isRedefineClassesSupported()) {
			throw new McpToolException("The agent is loaded but this JVM does not support class redefinition."); //$NON-NLS-1$
		}
		return new Attachment(loaded, "helperProcess", millis, jar); //$NON-NLS-1$
	}

	private static String runHelper(Path java, Path jar, int timeoutSeconds) throws McpToolException {
		String pid = Long.toString(ProcessHandle.current().pid());
		ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", jar.toString(), ATTACHER_CLASS, pid, //$NON-NLS-1$
				jar.toString()).redirectErrorStream(true);
		try {
			Process process = builder.start();
			process.getOutputStream().close();
			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
			}
			String output;
			try (InputStream in = process.getInputStream()) {
				output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
			}
			if (!finished) {
				throw new McpToolException("The attach helper did not finish within %d seconds and was killed. Output so far: %s" //$NON-NLS-1$
						.formatted(Integer.valueOf(timeoutSeconds), output));
			}
			if (process.exitValue() != 0) {
				throw new McpToolException("The attach helper failed with exit code %d: %s" //$NON-NLS-1$
						.formatted(Integer.valueOf(process.exitValue()), output));
			}
			return output;
		} catch (IOException e) {
			throw new McpToolException("Could not start the attach helper " + java, e); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the attach helper"); //$NON-NLS-1$
		}
	}

	private static Path defaultJava() {
		return Path.of(System.getProperty("java.home"), "bin", FileLocations.isWindows() ? "java.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Writes the agent jar from this bundle's own class files, replacing any earlier copy. */
	private static Path writeAgentJar() throws IOException {
		Path state = Platform.getStateLocation(FrameworkUtil.getBundle(HotSwapSupport.class)).toPath();
		Files.createDirectories(state);
		Path jar = state.resolve(JAR_NAME);
		Path temp = Files.createTempFile(state, "hotswap-agent", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
		Manifest manifest = new Manifest();
		Attributes main = manifest.getMainAttributes();
		main.put(Attributes.Name.MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
		main.putValue("Agent-Class", AGENT_CLASS); //$NON-NLS-1$
		main.putValue("Premain-Class", AGENT_CLASS); //$NON-NLS-1$
		main.putValue("Can-Redefine-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$
		main.putValue("Can-Retransform-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
			for (String name : List.of(AGENT_CLASS, ATTACHER_CLASS)) {
				String entry = name.replace('.', '/') + ".class"; //$NON-NLS-1$
				out.putNextEntry(new JarEntry(entry));
				try (InputStream in = HotSwapSupport.class.getResourceAsStream("/" + entry)) { //$NON-NLS-1$
					if (in == null) {
						throw new IOException("Class file " + entry + " is missing from this bundle"); //$NON-NLS-1$ //$NON-NLS-2$
					}
					in.transferTo(out);
				}
				out.closeEntry();
			}
		}
		Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return jar;
	}

	/** Every loaded class by binary name, whatever loaded it. */
	static Map<String, List<Class<?>>> loadedByName(Instrumentation instrumentation) {
		Map<String, List<Class<?>>> result = new HashMap<>();
		for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
			result.computeIfAbsent(loaded.getName(), key -> new ArrayList<>()).add(loaded);
		}
		return result;
	}
}
