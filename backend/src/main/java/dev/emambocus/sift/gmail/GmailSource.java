package dev.emambocus.sift.gmail;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.FeedSyncStore;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

	/** Where a message id opens in the Gmail web client, whichever mailbox folder it is filed under. */
	private static final String MESSAGE_URL = "https://mail.google.com/mail/u/0/#all/";

	private static final String UNREAD = "UNREAD";

	private static final String SENT = "SENT";

	/** A draft is unsent and changes as you type, and a chat is not mail. */
	private static final Set<String> NOT_MAIL = Set.of("DRAFT", "CHAT");

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

	@Override
	public List<IncomingItem> fetch(SourceCredential credential) {
		// a Google access token lives about an hour, so every sweep starts by renewing one that is due
		String accessToken = oauth.accessTokenFor(credential);

		GmailResponses.Profile me = client.fetchProfile(accessToken);
		if (me == null || me.emailAddress() == null) {
			throw new SourceUnavailableException("Google did not say which mailbox the token belongs to.");
		}

		// the profile call already answers it, so naming the mailbox costs nothing extra
		syncStore.rememberAccount(credential.getId(), me.emailAddress());

		UUID userId = credential.getUserId();
		GmailCursor stored = store.cursorFor(userId).orElseGet(GmailCursor::empty);

		List<IncomingItem> items = new ArrayList<>();
		GmailCursor cursor = readNewer(accessToken, stored, items);
		cursor = readOlder(accessToken, cursor, items);

		store.advance(userId, cursor);
		log.debug("read Gmail for {}: {} row(s), forward edge {}, floor {}{}", me.emailAddress(), items.size(),
				cursor.newest(), cursor.oldest(), cursor.backfillDone() ? ", mailbox complete" : "");
		return items;
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
				"msg:" + message.id(),
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
