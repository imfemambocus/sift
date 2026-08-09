package dev.emambocus.sift.gmail;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.FeedSyncStore;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceFetch;
import dev.emambocus.sift.sync.SourceReadState;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every message in a mailbox becomes a row, and the whole mailbox is read. There is no relevance
 * rule here and there is not going to be one.
 *
 * <p>Sift answers two problems. A source floods a mailbox, and a mailbox cannot then be searched.
 * GitLab's to-do list answers the first for GitLab. For mail the value is the second one, and it is
 * the only one: every message in the same feed, with a search that forgives a typo and takes scope
 * prefixes. A search can only find what was read, so anything held back from the feed is the one
 * thing this source must not do.
 *
 * <p>Two things are left out. Spam and trash, because the API leaves them out unless asked for.
 * Drafts and chats, because a draft is unsent and changes as you type, and a chat is not mail.
 */
@Component
class GmailSource implements NotificationSource {

	private static final Logger log = LoggerFactory.getLogger(GmailSource.class);

	static final String KIND_RECEIVED = "mail_received";

	static final String KIND_SENT = "mail_sent";

	/** Every Gmail row is one message, stored under its message id behind this. */
	static final String ID_PREFIX = "msg:";

	/** Where a message id opens in the Gmail web client, whichever mailbox folder it is filed under. */
	private static final String MESSAGE_URL = "https://mail.google.com/mail/u/0/#all/";

	private static final String UNREAD = "UNREAD";

	private static final String SENT = "SENT";

	/** A draft is unsent and changes as you type, and a chat is not mail. */
	private static final Set<String> NOT_MAIL = Set.of("DRAFT", "CHAT");

	/**
	 * Where a message goes when it leaves the mailbox. Sift never reads either, so a row for a message
	 * that lands in one disagrees with the mailbox only because of when it was thrown away.
	 */
	private static final List<String> OUT_OF_THE_MAILBOX = List.of("TRASH", "SPAM");

	/*
	 * a ceiling on one sweep, since every message costs a request of its own. the rest is read by a
	 * later sweep, because both edges of the cursor only ever widen the stretch already read.
	 */
	private static final int MAX_MESSAGES = 200;

	private static final String NO_SUBJECT = "(no subject)";

	private final GmailClient client;
	private final GmailOAuth oauth;
	private final GmailSyncStore store;
	private final FeedSyncStore syncStore;
	private final int maxPages;

	GmailSource(GmailClient client, GmailOAuth oauth, GmailSyncStore store, FeedSyncStore syncStore,
			SiftProperties properties) {

		this.client = client;
		this.oauth = oauth;
		this.store = store;
		this.syncStore = syncStore;
		this.maxPages = properties.sync().maxPages();
	}

	@Override
	public SourceType id() {
		return SourceType.GMAIL;
	}

	/*
	 * a mailbox is walked backwards a chunk at a time, so a connection spends its first hours with
	 * only part of its history in the feed. false before the first sweep as well, which is honest:
	 * nothing of it has been read yet.
	 */
	@Override
	public boolean historyComplete(SourceCredential credential) {
		return store.cursorFor(credential.getId()).map(GmailCursor::backfillDone).orElse(false);
	}

	@Override
	public SourceFetch fetch(SourceCredential credential) {
		// a Google access token lives about an hour, so every sweep starts by renewing one that is due
		String accessToken = oauth.accessTokenFor(credential);

		GmailResponses.Profile me = client.fetchProfile(accessToken);
		if (me == null || me.emailAddress() == null) {
			throw new SourceUnavailableException("Google did not say which mailbox the token belongs to.");
		}

		// the profile call already answers it, so naming the mailbox costs nothing extra
		syncStore.rememberAccount(credential.getId(), me.emailAddress());

		UUID credentialId = credential.getId();
		GmailCursor stored = store.cursorFor(credentialId).orElseGet(GmailCursor::empty);

		List<IncomingItem> items = new ArrayList<>();
		GmailCursor cursor = readNewer(accessToken, stored, items);
		cursor = readOlder(accessToken, cursor, items);

		Reconciled reconciled = reconcile(accessToken, stored, numberOf(me.historyId()), items);
		GmailCursor moved = cursor.resumingAt(reconciled.historyId());

		log.debug("read Gmail for {}: {} row(s), forward edge {}, floor {}{}", me.emailAddress(), items.size(),
				moved.newest(), moved.oldest(), moved.backfillDone() ? ", mailbox complete" : "");
		// the edges are written down only once these rows are stored: see SourceFetch
		return new SourceFetch(items, reconciled.state(), reconciled.gone(),
				() -> store.advance(credentialId, moved));
	}

	private record Reconciled(SourceReadState state, Set<String> gone, Long historyId) {

		static Reconciled nothing(Long historyId) {
			return new Reconciled(SourceReadState.NONE, Set.of(), historyId);
		}
	}

	/**
	 * What the mailbox itself has done to its messages since the last sweep: read them, put them back
	 * to unread, thrown them away, or taken them back out of the bin.
	 *
	 * <p>Gmail's own record of the changes, rather than a comparison against the mailbox: it is one
	 * request whatever the mailbox holds. It also carries Sift's own writes back, so a decision taken
	 * here and one taken there cannot fight over the same row.
	 *
	 * <p>The first sweep of a connection only records where to start, because nothing that happened
	 * before it existed is news, and every row it inserts is seeded from the message itself.
	 */
	private Reconciled reconcile(String accessToken, GmailCursor cursor, Long mailboxNow,
			List<IncomingItem> items) {

		if (cursor.historyId() == null) {
			return Reconciled.nothing(mailboxNow);
		}

		Optional<GmailClient.HistorySince> since = client.fetchHistory(accessToken, cursor.historyId(), maxPages);
		if (since.isEmpty()) {
			return new Reconciled(afterLosingTheHistory(accessToken, cursor), Set.of(), mailboxNow);
		}

		Set<String> read = new LinkedHashSet<>();
		Set<String> unread = new LinkedHashSet<>();
		Set<String> gone = new LinkedHashSet<>();
		Set<String> back = new LinkedHashSet<>();
		// oldest first, so the last thing that happened to a message is what stands
		for (GmailResponses.HistoryRecord record : since.get().records()) {
			label(record.labelsAdded(), UNREAD, unread, read);
			label(record.labelsRemoved(), UNREAD, read, unread);
			for (String out : OUT_OF_THE_MAILBOX) {
				label(record.labelsAdded(), out, gone, back);
				label(record.labelsRemoved(), out, back, gone);
			}
			deleted(record.messagesDeleted(), gone, back);
		}

		readAgain(accessToken, back, items);
		return new Reconciled(SourceReadState.changed(read, unread), gone, since.get().historyId());
	}

	/**
	 * Google keeps its history for about a week, so an instance that was off for longer is told to
	 * start again. What is left is the mailbox itself: every message it still counts as unread names
	 * the rest as read, which is the whole answer in about one request.
	 *
	 * <p>A mailbox with more unread mail than one sweep may list answers nothing, since a partial
	 * answer would say that mail Sift never saw had been read.
	 */
	private SourceReadState afterLosingTheHistory(String accessToken, GmailCursor cursor) {
		log.info("Gmail no longer holds its history back to {}; comparing against the mailbox instead",
				cursor.historyId());

		Optional<Set<String>> unread = client.listUnread(accessToken, maxPages);
		if (unread.isEmpty()) {
			log.warn("too much unread mail to list in one sweep, so read state there is left alone");
			return SourceReadState.NONE;
		}
		return SourceReadState.only(unread.get().stream().map(ID_PREFIX::concat).collect(Collectors.toSet()));
	}

	/**
	 * A message taken back out of the bin becomes a row again. Without it, removing the row when a
	 * message is thrown away would be one-way, and Sift never looks under its own floor again.
	 */
	private void readAgain(String accessToken, Set<String> back, List<IncomingItem> items) {
		for (String sourceId : back) {
			GmailResponses.Message message = client.fetchMessage(accessToken, sourceId.substring(ID_PREFIX.length()));
			Optional<Instant> arrived = arrivalOf(message);
			if (arrived.isPresent() && !isNotMail(message)) {
				items.add(toIncomingItem(message, arrived.get()));
			}
		}
	}

	/** One label moving a message from one set to the other, in the order the mailbox recorded it. */
	private static void label(List<GmailResponses.LabelChange> changes, String label, Set<String> into,
			Set<String> outOf) {

		if (changes == null) {
			return;
		}
		for (GmailResponses.LabelChange change : changes) {
			if (change.message() == null || change.labelIds() == null || !change.labelIds().contains(label)) {
				continue;
			}
			move(ID_PREFIX + change.message().id(), into, outOf);
		}
	}

	private static void deleted(List<GmailResponses.MessageChange> changes, Set<String> gone, Set<String> back) {
		if (changes == null) {
			return;
		}
		for (GmailResponses.MessageChange change : changes) {
			if (change.message() != null) {
				move(ID_PREFIX + change.message().id(), gone, back);
			}
		}
	}

	private static void move(String sourceId, Set<String> into, Set<String> outOf) {
		into.add(sourceId);
		outOf.remove(sourceId);
	}

	private static Long numberOf(String historyId) {
		if (historyId == null) {
			return null;
		}
		try {
			return Long.valueOf(historyId);
		}
		catch (NumberFormatException ex) {
			log.warn("Gmail sent an unreadable history id: {}", historyId);
			return null;
		}
	}

	/**
	 * The first read of a mailbox takes its newest chunk, which seeds both edges. Every read after it
	 * takes what arrived since the forward edge.
	 *
	 * <p>The oldest of those is read first, so that what Sift has read stays one unbroken stretch even
	 * when more has arrived than one sweep can hold. Gmail lists newest first, so the end of the list
	 * is the oldest of it.
	 */
	private GmailCursor readNewer(String accessToken, GmailCursor cursor, List<IncomingItem> items) {
		if (!cursor.started()) {
			return read(accessToken, client.listMessages(accessToken, "", maxPages, MAX_MESSAGES), cursor, items);
		}

		List<GmailResponses.MessageRef> arrived =
				client.listMessages(accessToken, "after:" + cursor.newest().getEpochSecond(), maxPages);
		return read(accessToken, oldestOf(arrived, MAX_MESSAGES), cursor, items);
	}

	/**
	 * One chunk below the floor, which is what walks a mailbox back to its beginning. Gmail lists
	 * newest first, so what it answers here is the chunk immediately under the floor.
	 */
	private GmailCursor readOlder(String accessToken, GmailCursor cursor, List<IncomingItem> items) {
		if (cursor.backfillDone() || cursor.oldest() == null) {
			return cursor;
		}

		List<GmailResponses.MessageRef> older = client.listMessages(
				accessToken, "before:" + cursor.oldest().getEpochSecond(), maxPages, MAX_MESSAGES);
		if (older.isEmpty()) {
			return cursor.completed();
		}

		GmailCursor moved = read(accessToken, older, cursor, items);
		if (moved.oldest() != null && moved.oldest().isBefore(cursor.oldest())) {
			return moved;
		}

		/*
		 * gmail reads before: to the second, so one second holding more mail than a whole chunk would
		 * ask for the same messages for ever. stepping the floor under that second is what ends it.
		 */
		log.warn("Gmail backfill did not move below {}; stepping the floor back one second", cursor.oldest());
		return moved.floorAt(cursor.oldest().minusSeconds(1));
	}

	private GmailCursor read(String accessToken, List<GmailResponses.MessageRef> refs, GmailCursor cursor,
			List<IncomingItem> items) {

		GmailCursor moved = cursor;
		for (GmailResponses.MessageRef ref : refs) {
			GmailResponses.Message message = client.fetchMessage(accessToken, ref.id());
			Optional<Instant> arrived = arrivalOf(message);
			if (arrived.isEmpty()) {
				continue;
			}
			moved = moved.covering(arrived.get());
			if (isNotMail(message)) {
				continue;
			}
			items.add(toIncomingItem(message, arrived.get()));
		}
		return moved;
	}

	/** The end of a newest-first list, which is the oldest {@code count} of it. */
	private static List<GmailResponses.MessageRef> oldestOf(List<GmailResponses.MessageRef> refs, int count) {
		return refs.size() <= count ? refs : refs.subList(refs.size() - count, refs.size());
	}

	private IncomingItem toIncomingItem(GmailResponses.Message message, Instant arrived) {
		boolean sent = labels(message).contains(SENT);
		/*
		 * the other party: whoever sent mail you received, and whoever received mail you sent. it is
		 * what the row is about, and it is what puts their address in the search haystack.
		 */
		String party = header(message, sent ? "To" : "From");
		return new IncomingItem(
				ID_PREFIX + message.id(),
				sent ? KIND_SENT : KIND_RECEIVED,
				subjectOf(message),
				message.snippet(),
				personName(party),
				null,
				personAddress(party),
				null,
				MESSAGE_URL + message.id(),
				/*
				 * the thread, not the url. every message of every conversation lives at the same path
				 * and differs only in the fragment, so the rule that strips the fragment would make one
				 * group of the whole mailbox.
				 */
				message.threadId(),
				arrived,
				arrived,
				// the mailbox's own answer, and only where the row is new: after that Sift owns it
				!labels(message).contains(UNREAD),
				null,
				// a message happened once. the next sweep not listing it says nothing at all.
				false);
	}

	private static Optional<Instant> arrivalOf(GmailResponses.Message message) {
		if (message == null || message.id() == null || message.internalDate() == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.ofEpochMilli(Long.parseLong(message.internalDate())));
		}
		catch (NumberFormatException ex) {
			log.warn("Gmail sent an unreadable internalDate for message {}", message.id());
			return Optional.empty();
		}
	}

	private static boolean isNotMail(GmailResponses.Message message) {
		return labels(message).stream().anyMatch(NOT_MAIL::contains);
	}

	private static List<String> labels(GmailResponses.Message message) {
		return message.labelIds() == null ? List.of() : message.labelIds();
	}

	private static String subjectOf(GmailResponses.Message message) {
		String subject = header(message, "Subject");
		return subject == null || subject.isBlank() ? NO_SUBJECT : subject;
	}

	private static String header(GmailResponses.Message message, String name) {
		if (message.payload() == null || message.payload().headers() == null) {
			return null;
		}
		return message.payload().headers().stream()
				.filter(header -> name.equalsIgnoreCase(header.name()))
				.map(GmailResponses.Header::value)
				.findFirst()
				.orElse(null);
	}

	/**
	 * The display name out of {@code "Ada Lovelace" <ada@uni.lu>}, or the address when there is none.
	 * Written by hand rather than with a mail parser, because one header field does not justify a
	 * dependency and the shape that matters is this one.
	 *
	 * <p>A header naming several people keeps the first of them, which is what a row has room to show.
	 */
	private static String personName(String header) {
		String first = firstAddress(header);
		if (first == null) {
			return null;
		}
		int bracket = first.indexOf('<');
		if (bracket <= 0) {
			return unquote(first.trim());
		}
		String name = unquote(first.substring(0, bracket).trim());
		return name.isEmpty() ? personAddress(header) : name;
	}

	private static String personAddress(String header) {
		String first = firstAddress(header);
		if (first == null) {
			return null;
		}
		int open = first.indexOf('<');
		int close = first.lastIndexOf('>');
		if (open < 0 || close <= open) {
			return first.trim();
		}
		return first.substring(open + 1, close).trim();
	}

	/** A comma inside a quoted display name is not a separator, so the quotes are counted. */
	private static String firstAddress(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		boolean quoted = false;
		for (int i = 0; i < header.length(); i++) {
			char at = header.charAt(i);
			if (at == '"') {
				quoted = !quoted;
			}
			else if (at == ',' && !quoted) {
				return header.substring(0, i);
			}
		}
		return header;
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1).trim();
		}
		return value;
	}
}
