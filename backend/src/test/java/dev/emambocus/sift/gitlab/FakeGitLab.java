package dev.emambocus.sift.gitlab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in GitLab on an ephemeral port, driven by JSON strings a test sets per path.
 *
 * <p>The JDK's own server rather than a mocked {@code RestClient}: {@link GitLabClient} clones the
 * builder and installs its own request factory for the timeouts, so anything that intercepts at the
 * builder would be testing a different client than production uses. This also exercises the real
 * paging, the {@code x-next-page} header and the status translation.
 */
final class FakeGitLab implements AutoCloseable {

	private final HttpServer server;
	private final Map<String, String> bodies = new HashMap<>();
	private final Map<String, Integer> statuses = new HashMap<>();
	private final Map<String, AtomicInteger> hits = new HashMap<>();

	FakeGitLab() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not start the stand-in GitLab", ex);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Everything not given a body answers with an empty list, which is what "nothing there" is. */
	FakeGitLab on(String path, String json) {
		bodies.put(path, json);
		return this;
	}

	FakeGitLab failing(String path, int status) {
		statuses.put(path, status);
		return this;
	}

	int hits(String path) {
		AtomicInteger count = hits.get(path);
		return count == null ? 0 : count.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

		Integer failure = statuses.get(path);
		if (failure != null) {
			send(exchange, failure, "{\"message\":\"nope\"}");
			return;
		}
		send(exchange, 200, bodies.getOrDefault(path, "[]"));
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
		// always the last page: these fixtures are small, and paging itself has its own suite
		exchange.getResponseHeaders().put("x-next-page", List.of(""));
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
