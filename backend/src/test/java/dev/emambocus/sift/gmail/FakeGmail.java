package dev.emambocus.sift.gmail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in Google on an ephemeral port, serving the token endpoint and the three Gmail calls Sift
 * makes.
 *
 * <p>The JDK's own server rather than a mocked {@code RestClient}, for the reason {@code FakeGitLab}
 * gives. The client installs its own request factory for the timeouts, and anything intercepting at
 * the builder would test a client production does not use.
 *
 * <p>It honours {@code after:} and {@code before:} in the search itself rather than answering a fixed
 * list, and it answers newest first as Gmail does. Both are the parts of this adapter most worth
 * proving: a stub that ignored the query, or that answered in an order of its own, would pass a test
 * that only checked the rows and would say nothing about how the mailbox is walked.
 */
public final class FakeGmail implements AutoCloseable {

	/**
	 * A part of a message that carries a file. Gmail marks the signature image and the logo of a
	 * newsletter as inline, which is not the same as somebody attaching something.
	 */
	public record File(String name, boolean inline, boolean nested) {

		/** Somebody attached it: a part of the message itself. */
		public static File attached(String name) {
			return new File(name, false, false);
		}

		/** An image the message draws, which sits within the part that draws it. */
		public static File embedded(String name) {
			return new File(name, true, true);
		}

		/** Attached to a message that was forwarded: a part of a part. */
		public static File forwarded(String name) {
			return new File(name, false, true);
		}
	}

	/**
	 * One message, as the fixtures describe it. Labels are Gmail's own vocabulary.
	 *
	 * @param body what the message says, and null for one with no text part at all
	 * @param html true when {@code body} is the markup of an HTML-only message, which is what a great
	 *     deal of mail sent by machines is
	 */
	public record Msg(String id, String threadId, long arrivedAtMillis, String from, String to, String subject,
			String snippet, List<String> labels, List<File> files, String body, boolean html) {

		public static Msg unread(String id, String threadId, long arrivedAtMillis, String from, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, from, null, subject, "a snippet",
					List.of("INBOX", "UNREAD"), List.of(), null, false);
		}

		public static Msg read(String id, String threadId, long arrivedAtMillis, String from, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, from, null, subject, "a snippet",
					List.of("INBOX"), List.of(), null, false);
		}

		/** Mail you wrote: Gmail labels it SENT, and the recipient is who it is about. */
		public static Msg sent(String id, String threadId, long arrivedAtMillis, String to, String subject) {
			return new Msg(id, threadId, arrivedAtMillis, "me@uni.lu", to, subject, "a snippet",
					List.of("SENT"), List.of(), null, false);
		}

		public Msg labelled(String... labels) {
			return new Msg(id, threadId, arrivedAtMillis, from, to, subject, snippet, List.of(labels), files,
					body, html);
		}

		public Msg carrying(File... files) {
			return new Msg(id, threadId, arrivedAtMillis, from, to, subject, snippet, labels, List.of(files),
					body, html);
		}

		/** What the message says, as a text part beside whatever else it carries. */
		public Msg saying(String body) {
			return new Msg(id, threadId, arrivedAtMillis, from, to, subject, snippet, labels, files, body, false);
		}

		/** The same, for a message whose only part is markup. */
		public Msg sayingInHtml(String markup) {
			return new Msg(id, threadId, arrivedAtMillis, from, to, subject, snippet, labels, files, markup, true);
		}
	}

	/**
	 * One thing the mailbox recorded happening to a message, which is what the history endpoint
	 * answers with. {@code field} is Gmail's own name for the kind of change, and {@code label} is null
	 * for a message that was deleted outright.
	 */
	private record Change(long historyId, String messageId, String field, String label) {
	}

	private static final String UNREAD = "UNREAD";

	private static final String TRASH = "TRASH";

	private static final String ADDED = "labelsAdded";

	private static final String REMOVED = "labelsRemoved";

	private static final String DELETED = "messagesDeleted";

	/** What the list endpoint leaves out, because the real one does unless it is asked otherwise. */
	private static final Set<String> OUT_OF_THE_MAILBOX = Set.of(TRASH, "SPAM");

	private static final long FIRST_HISTORY_ID = 1000;

	private final HttpServer server;
	private final Map<String, Msg> messages = new LinkedHashMap<>();
	private final Map<String, AtomicInteger> hits = new LinkedHashMap<>();
	private final List<String> modified = new ArrayList<>();
	private final List<Change> changes = new ArrayList<>();
	private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();

	private String accessToken = "live-access";
	private String tokenResponse = """
			{"access_token": "live-access", "expires_in": 3600}
			""";
	private int listStatus = 200;
	private long historyId = FIRST_HISTORY_ID;
	private boolean historyForgotten;
	private boolean unreadFloods;

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
		modified.clear();
		changes.clear();
		accessToken = "live-access";
		tokenResponse = """
				{"access_token": "live-access", "expires_in": 3600}
				""";
		listStatus = 200;
		historyId = FIRST_HISTORY_ID;
		historyForgotten = false;
		unreadFloods = false;
		release();
	}

	/**
	 * Answers nothing until {@link #release()}. A test can hold a read open and look at what the
	 * app says while one is running. The token endpoint is left answering: a test that holds the whole
	 * server holds the authorization as well, and the wait would prove nothing about reading.
	 */
	public void hold() {
		gate.set(new CountDownLatch(1));
	}

	public void release() {
		CountDownLatch held = gate.getAndSet(null);
		if (held != null) {
			held.countDown();
		}
	}

	public FakeGmail deliver(Msg... incoming) {
		for (Msg message : incoming) {
			messages.put(message.id(), message);
		}
		return this;
	}

	/** Which bearer token the API accepts. It is how a test proves a renewal was really applied. */
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

	/** Somebody reading the message in Gmail itself, which is the direction a push from Sift cannot see. */
	public FakeGmail readInGmail(String id) {
		return relabel(id, UNREAD, false);
	}

	public FakeGmail unreadInGmail(String id) {
		return relabel(id, UNREAD, true);
	}

	public FakeGmail trashInGmail(String id) {
		return relabel(id, TRASH, true);
	}

	public FakeGmail restoreInGmail(String id) {
		return relabel(id, TRASH, false);
	}

	/** Emptying the bin, which takes the message out of the mailbox altogether. */
	public FakeGmail deleteInGmail(String id) {
		if (messages.remove(id) == null) {
			throw new IllegalArgumentException("no message " + id + " to delete");
		}
		historyId++;
		changes.add(new Change(historyId, id, DELETED, null));
		return this;
	}

	/** Gmail keeps its history for about a week. An instance off for longer is told to start again. */
	public FakeGmail forgettingHistory() {
		this.historyForgotten = true;
		return this;
	}

	/** More unread mail than any sweep may page through: no complete answer about it exists. */
	public FakeGmail floodingUnread() {
		this.unreadFloods = true;
		return this;
	}

	private FakeGmail relabel(String id, String label, boolean add) {
		Msg message = messages.get(id);
		if (message == null) {
			throw new IllegalArgumentException("no message " + id + " to relabel");
		}
		List<String> labels = new ArrayList<>(message.labels());
		labels.remove(label);
		if (add) {
			labels.add(label);
		}
		messages.put(id, message.labelled(labels.toArray(String[]::new)));
		historyId++;
		changes.add(new Change(historyId, id, add ? ADDED : REMOVED, label));
		return this;
	}

	/** The raw body of each batchModify. A test reads which ids went, and in which direction. */
	public List<String> modifications() {
		return List.copyOf(modified);
	}

	public int hits(String path) {
		AtomicInteger count = hits.get(path);
		return count == null ? 0 : count.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

		if ("/token".equals(path)) {
			send(exchange, 200, tokenResponse);
			return;
		}
		waitIfHeld();
		if (!authorized(exchange)) {
			send(exchange, 401, "{\"error\":\"invalid_token\"}");
			return;
		}
		if (path.equals("/gmail/v1/users/me/profile")) {
			send(exchange, 200, "{\"emailAddress\": \"sam@uni.lu\", \"historyId\": \"" + historyId + "\"}");
			return;
		}
		if (path.equals("/gmail/v1/users/me/history")) {
			if (historyForgotten) {
				send(exchange, 404, "{\"error\":\"startHistoryId is too old\"}");
				return;
			}
			send(exchange, 200, historyAfter(numberIn(exchange.getRequestURI().getQuery(), "startHistoryId")));
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
		if (path.equals("/gmail/v1/users/me/messages/batchModify")) {
			modified.add(body);
			send(exchange, 204, "");
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

	private void waitIfHeld() {
		CountDownLatch held = gate.get();
		if (held == null) {
			return;
		}
		try {
			held.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean authorized(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		return ("Bearer " + accessToken).equals(header);
	}

	/** Newest first, like Gmail, honouring both bounds and `is:unread`, and leaving the bin out. */
	private String listFor(String query) {
		long after = secondsIn(query, "after");
		long before = secondsIn(query, "before");
		// whether the colon arrived encoded or not, exactly as the date operators are read below
		boolean onlyUnread = query != null && (query.contains("is%3Aunread") || query.contains("is:unread"));
		List<Msg> matching = new ArrayList<>(messages.values().stream()
				.filter(message -> message.labels().stream().noneMatch(OUT_OF_THE_MAILBOX::contains))
				.filter(message -> !onlyUnread || message.labels().contains(UNREAD))
				.filter(message -> message.arrivedAtMillis() / 1000 > after)
				.filter(message -> before == 0 || message.arrivedAtMillis() / 1000 < before)
				.sorted(Comparator.comparingLong(Msg::arrivedAtMillis).reversed())
				.toList());

		/*
		 * a page token that never runs out: a caller paging for a complete answer never gets one.
		 * it is the only way to stand in for a mailbox with more unread mail than a sweep may list.
		 */
		if (onlyUnread && unreadFloods) {
			return "{\"messages\":[{\"id\":\"flood\",\"threadId\":\"flood\"}],\"nextPageToken\":\"more\"}";
		}

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

	/** Every label change after a point, oldest first, as Gmail answers them. */
	private String historyAfter(long start) {
		StringBuilder json = new StringBuilder("{\"history\":[");
		boolean first = true;
		for (Change change : changes) {
			if (change.historyId() <= start) {
				continue;
			}
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append("{\"id\":\"").append(change.historyId()).append("\",\"").append(change.field())
					.append("\":[{\"message\":{\"id\":\"").append(change.messageId()).append("\"}");
			if (change.label() != null) {
				json.append(",\"labelIds\":[\"").append(change.label()).append("\"]");
			}
			json.append("}]}");
		}
		return json.append("],\"historyId\":\"").append(historyId).append("\"}").toString();
	}

	private static long numberIn(String query, String parameter) {
		if (query == null) {
			return 0;
		}
		for (String part : query.split("&")) {
			if (part.startsWith(parameter + "=")) {
				return Long.parseLong(part.substring(parameter.length() + 1));
			}
		}
		return 0;
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
				 "payload": {"mimeType": "multipart/mixed", "headers": [
				   {"name": "Subject", "value": "%s"},
				   {"name": "From", "value": "%s"}%s
				 ]%s}}
				""".formatted(message.id(), message.threadId(), labels, message.arrivedAtMillis(),
				escape(message.snippet()), escape(message.subject()), escape(message.from()), to,
				partsJson(message));
	}

	/*
	 * the shape a real message with a file in it has: a part somebody attached sits beside the text,
	 * and an inline image or anything that came with a forwarded message sits within it. both depths
	 * are here on purpose, since a reader that looked at only one of them would pass on the other.
	 */
	private static String partsJson(Msg message) {
		if (message.files().isEmpty()) {
			return message.body() == null ? "" : ", \"parts\": [" + textPartJson(message) + "]";
		}
		StringBuilder beside = new StringBuilder();
		StringBuilder within = new StringBuilder();
		for (File file : message.files()) {
			StringBuilder into = file.nested() ? within : beside;
			into.append(',').append(fileJson(file));
		}
		return """
				, "parts": [
				  {"mimeType": "multipart/related", "filename": "",
				   "parts": [%s%s]}%s]"""
				.formatted(textPartJson(message), within, beside);
	}

	/**
	 * The part that carries what the message says. Without a body it is the empty text part a message
	 * with only a file in it has, which is also what Gmail answers for a part too large to inline.
	 *
	 * <p>The data is unpadded base64url, as Gmail sends it. A reader that assumed padding fails.
	 */
	private static String textPartJson(Msg message) {
		if (message.body() == null) {
			return "{\"mimeType\": \"text/plain\", \"filename\": \"\"}";
		}
		return """
				{"mimeType": "%s", "filename": "", "body": {"data": "%s"}}"""
				.formatted(message.html() ? "text/html" : "text/plain",
						Base64.getUrlEncoder().withoutPadding()
								.encodeToString(message.body().getBytes(StandardCharsets.UTF_8)));
	}

	private static String fileJson(File file) {
		return """
				{"mimeType": "application/octet-stream", "filename": "%s",
				 "headers": [{"name": "Content-Disposition", "value": "%s; filename=\\"%s\\""}]}"""
				.formatted(escape(file.name()), file.inline() ? "inline" : "attachment", escape(file.name()));
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
