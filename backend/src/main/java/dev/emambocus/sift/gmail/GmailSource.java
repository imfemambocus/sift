package dev.emambocus.sift.gmail;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every message in a mailbox becomes a row. There is no relevance rule here, on purpose.
 *
 * <p>Sift answers two problems. A source floods a mailbox, and a mailbox cannot then be searched.
 * GitLab's to-do list answers the first for GitLab. For mail the value is the second one: every
 * message in the same feed, with a search that forgives a typo and takes scope prefixes. Deciding
 * which of your own mail you are allowed to see is what a mailbox already claims to do.
 *
 * <p>Three things are left out, and none of them is a judgement about relevance. Spam and trash,
 * because the API leaves them out unless asked for. Your own sent mail and drafts, for the same
 * reason a reply of yours raises no GitLab row: it is not news to whoever wrote it.
 */
@Component
class GmailSource implements NotificationSource {

	private static final Logger log = LoggerFactory.getLogger(GmailSource.class);

	static final String KIND = "mail_received";

	/** Where a message id opens in the Gmail web client, whichever mailbox folder it is filed under. */
	private static final String MESSAGE_URL = "https://mail.google.com/mail/u/0/#all/";

	private static final String UNREAD = "UNREAD";

	/** Written by you, so it is not news to you. The same rule as a GitLab reply of your own. */
	private static final Set<String> NOT_NEWS = Set.of("SENT", "DRAFT", "CHAT");

	/*
	 * a ceiling on one sweep, since every message costs a request of its own. anything above it is
	 * read by the next sweep, because the watermark only moves as far as what was actually read.
	 */
	private static final int MAX_MESSAGES = 200;

	private static final String NO_SUBJECT = "(no subject)";

	private final GmailClient client;
	private final GmailOAuth oauth;
	private final GmailSyncStore store;
	private final GmailProperties config;
	private final Clock clock;
	private final int maxPages;

	GmailSource(GmailClient client, GmailOAuth oauth, GmailSyncStore store, GmailProperties config,
			Clock clock, SiftProperties properties) {

		this.client = client;
		this.oauth = oauth;
		this.store = store;
		this.config = config;
		this.clock = clock;
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

		/*
		 * the first read takes a window, so connecting a ten-year-old mailbox does not try to read all
		 * of it. every read after that takes everything since the newest message already seen.
		 */
		Instant since = store.newestSeen(credential.getUserId())
				.orElseGet(() -> clock.instant().minus(config.window()));

		List<GmailResponses.MessageRef> refs =
				client.listMessages(accessToken, "after:" + since.getEpochSecond(), maxPages, MAX_MESSAGES);

		List<IncomingItem> items = new ArrayList<>();
		Instant newest = since;
		for (GmailResponses.MessageRef ref : refs) {
			GmailResponses.Message message = client.fetchMessage(accessToken, ref.id());
			Optional<Instant> arrived = arrivalOf(message);
			if (arrived.isEmpty()) {
				continue;
			}
			if (arrived.get().isAfter(newest)) {
				newest = arrived.get();
			}
			if (isMine(message)) {
				continue;
			}
			items.add(toIncomingItem(message, arrived.get()));
		}

		/*
		 * only as far as what was actually read, and only forwards. a sweep that stopped at the cap
		 * therefore leaves the rest for the next one instead of stepping over it.
		 */
		store.remember(credential.getUserId(), newest);
		log.debug("read {} Gmail message(s) for {}, {} of them new to the feed",
				refs.size(), me.emailAddress(), items.size());
		return items;
	}

	private IncomingItem toIncomingItem(GmailResponses.Message message, Instant arrived) {
		String from = header(message, "From");
		return new IncomingItem(
				"msg:" + message.id(),
				KIND,
				subjectOf(message),
				message.snippet(),
				senderName(from),
				null,
				senderAddress(from),
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

	private static boolean isMine(GmailResponses.Message message) {
		return labels(message).stream().anyMatch(NOT_NEWS::contains);
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
	 */
	private static String senderName(String from) {
		if (from == null || from.isBlank()) {
			return null;
		}
		int bracket = from.indexOf('<');
		if (bracket <= 0) {
			return unquote(from.trim());
		}
		String name = unquote(from.substring(0, bracket).trim());
		return name.isEmpty() ? senderAddress(from) : name;
	}

	private static String senderAddress(String from) {
		if (from == null || from.isBlank()) {
			return null;
		}
		int open = from.indexOf('<');
		int close = from.lastIndexOf('>');
		if (open < 0 || close <= open) {
			return from.trim();
		}
		return from.substring(open + 1, close).trim();
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1).trim();
		}
		return value;
	}
}
