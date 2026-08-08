package dev.emambocus.sift.gmail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in Google on an ephemeral port, serving the token endpoint and the three Gmail calls Sift
 * makes.
 *
 * <p>The JDK's own server rather than a mocked {@code RestClient}, for the reason {@code FakeGitLab}
 * gives: the client installs its own request factory for the timeouts, so anything intercepting at
 * the builder would test a client production does not use.
 *
 * <p>It honours {@code after:} and {@code before:} in the search itself rather than answering a fixed
 * list, and it answers newest first as Gmail does. Both are the parts of this adapter most worth
 * proving: a stub that ignored the query, or that answered in an order of its own, would pass a test
 * that only checked the rows and would say nothing about how the mailbox is walked.
 */
public final class FakeGmail implements AutoCloseable {

	/** One message, as the fixtures describe it. Labels are Gmail's own vocabulary. */
	public record Msg(String id, String threadId, long arrivedAtMillis, String from, String to, String subject,
			String snippet, List<String> labels) {

		public static Msg unread(String id, String threadId, long arrivedAtMillis, String from, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, from, null, subject, "a snippet",
					List.of("INBOX", "UNREAD"));
		}

		public static Msg read(String id, String threadId, long arrivedAtMillis, String from, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, from, null, subject, "a snippet", List.of("INBOX"));
		}

		/** Mail you wrote: Gmail labels it SENT, and the recipient is who it is about. */
		public static Msg sent(String id, String threadId, long arrivedAtMillis, String to, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, "me@uni.lu", to, subject, "a snippet", List.of("SENT"));
		}

		public Msg labelled(String... labels) {
			return new Msg(id, threadId, arrivedAtMillis, from, to, subject, snippet, List.of(labels));
		}
	}

	private final HttpServer server;
	private final Map<String, Msg> messages = new LinkedHashMap<>();
	private final Map<String, AtomicInteger> hits = new LinkedHashMap<>();

	private String accessToken = "live-access";
	private String tokenResponse = """
			{"access_token": "live-access", "expires_in": 3600}
			""";
	private int listStatus = 200;

	public FakeGmail() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not start the stand-in Gmail", ex);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Back to an empty mailbox that accepts the default token, between tests sharing one server. */
	public void reset() {
		messages.clear();
		hits.clear();
		accessToken = "live-access";
		tokenResponse = """
				{"access_token": "live-access", "expires_in": 3600}
				""";
		listStatus = 200;
	}

	public FakeGmail deliver(Msg... incoming) {
		for (Msg message : incoming) {
			messages.put(message.id(), message);
		}
		return this;
	}

	/** Which bearer token the API accepts, so a test can prove a renewal was really applied. */
	public FakeGmail accepting(String token) {
		this.accessToken = token;
		return this;
	}

	public FakeGmail answeringToken(String json) {
		this.tokenResponse = json;
		return this;
	}

	public FakeGmail failingList(int status) {
		this.listStatus = status;
		return this;
	}

	public int hits(String path) {
		AtomicInteger count = hits.get(path);
		return count == null ? 0 : count.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
		exchange.getRequestBody().readAllBytes();

		if ("/token".equals(path)) {
			send(exchange, 200, tokenResponse);
			return;
		}
		if (!authorized(exchange)) {
			send(exchange, 401, "{\"error\":\"invalid_token\"}");
			return;
		}
		if (path.equals("/gmail/v1/users/me/profile")) {
			send(exchange, 200, "{\"emailAddress\": \"isfaaq@uni.lu\"}");
			return;
		}
		if (path.equals("/gmail/v1/users/me/messages")) {
			if (listStatus != 200) {
				send(exchange, listStatus, "{\"error\":\"nope\"}");
				return;
			}
			send(exchange, 200, listFor(exchange.getRequestURI().getQuery()));
			return;
		}
		if (path.startsWith("/gmail/v1/users/me/messages/")) {
			Msg message = messages.get(path.substring(path.lastIndexOf('/') + 1));
			if (message == null) {
				send(exchange, 404, "{\"error\":\"no such message\"}");
				return;
			}
			send(exchange, 200, messageJson(message));
			return;
		}
		send(exchange, 404, "{\"error\":\"no such path\"}");
	}

	private boolean authorized(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		return ("Bearer " + accessToken).equals(header);
	}

	/** Newest first, like Gmail, and honouring both bounds in the query. */
	private String listFor(String query) {
		long after = secondsIn(query, "after");
		long before = secondsIn(query, "before");
		List<Msg> matching = new ArrayList<>(messages.values().stream()
				.filter(message -> message.arrivedAtMillis() / 1000 > after)
				.filter(message -> before == 0 || message.arrivedAtMillis() / 1000 < before)
				.sorted(Comparator.comparingLong(Msg::arrivedAtMillis).reversed())
				.toList());

		StringBuilder json = new StringBuilder("{\"messages\":[");
		for (int i = 0; i < matching.size(); i++) {
			Msg message = matching.get(i);
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"id\":\"").append(message.id())
					.append("\",\"threadId\":\"").append(message.threadId()).append("\"}");
		}
		return json.append("]}").toString();
	}

	/** The seconds against one Gmail search operator, whether the colon arrived encoded or not. */
	private static long secondsIn(String query, String operator) {
		if (query == null) {
			return 0;
		}
		for (String part : query.split("&")) {
			for (String form : new String[] {operator + "%3A", operator + ":"}) {
				int marker = part.indexOf(form);
				if (marker >= 0) {
					return Long.parseLong(part.substring(marker + form.length()));
				}
			}
		}
		return 0;
	}

	private static String messageJson(Msg message) {
		StringBuilder labels = new StringBuilder();
		for (String label : message.labels()) {
			if (!labels.isEmpty()) {
				labels.append(',');
			}
			labels.append('"').append(label).append('"');
		}
		String to = message.to() == null ? ""
				: ",{\"name\": \"To\", \"value\": \"" + escape(message.to()) + "\"}";
		return """
				{"id": "%s", "threadId": "%s", "labelIds": [%s], "internalDate": "%d",
				 "snippet": "%s",
				 "payload": {"headers": [
				   {"name": "Subject", "value": "%s"},
				   {"name": "From", "value": "%s"}%s
				 ]}}
				""".formatted(message.id(), message.threadId(), labels, message.arrivedAtMillis(),
				escape(message.snippet()), escape(message.subject()), escape(message.from()), to);
	}

	// a From header legitimately contains quotes, and an unescaped one makes the whole body unreadable
	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
