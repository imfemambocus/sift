package dev.emambocus.sift.gmail;

import dev.emambocus.sift.sources.SourceHttp;
import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The three Gmail calls Sift makes, and one translation of every failure into the two cases the
 * sweep tells apart.
 *
 * <p>Listing and reading are separate calls in this API: a list page carries ids and nothing else,
 * so a subject line costs a request of its own. That is what bounds a sweep, and it is why the
 * adapter asks only for messages it has not already seen.
 */
@Component
class GmailClient {

	private static final Logger log = LoggerFactory.getLogger(GmailClient.class);

	private static final int PER_PAGE = 100;

	/** How many ids one batchModify call takes. */
	static final int BATCH_LIMIT = 1000;

	private static final String UNREAD_LABEL = "UNREAD";

	private final SourceHttp http;
	private final GmailProperties config;

	GmailClient(SourceHttp http, GmailProperties config) {
		this.http = http;
		this.config = config;
	}

	/** The cheapest possible check that the token still works, before any paging. */
	GmailResponses.Profile fetchProfile(String accessToken) {
		return execute(() -> client(accessToken)
				.get()
				.uri("/gmail/v1/users/me/profile")
				.retrieve()
				.body(GmailResponses.Profile.class), "the mailbox profile");
	}

	/**
	 * Every id matching a Gmail search, newest first, bounded only by the page cap.
	 *
	 * <p>Spam and trash are excluded, because the API leaves them out unless asked for them. That is
	 * the one narrowing Sift does to a mailbox, and it is the mailbox's own answer rather than a rule
	 * of ours.
	 *
	 * <p>Listing is the cheap half of this API. A caller that has to know the oldest of a set can
	 * afford to list all of it and read only the end.
	 */
	List<GmailResponses.MessageRef> listMessages(String accessToken, String query, int maxPages) {
		return list(accessToken, query, maxPages, Integer.MAX_VALUE).messages();
	}

	List<GmailResponses.MessageRef> listMessages(String accessToken, String query, int maxPages, int limit) {
		return list(accessToken, query, maxPages, limit).messages();
	}

	/**
	 * Every unread id the mailbox still holds, or empty when there are more of them than one sweep may
	 * list. The caller uses it to work out what has been read there, and a partial answer would say
	 * that mail it never saw had been read. A partial answer is refused rather than trimmed.
	 */
	Optional<Set<String>> listUnread(String accessToken, int maxPages) {
		Listing unread = list(accessToken, "is:unread", maxPages, Integer.MAX_VALUE);
		if (!unread.complete()) {
			return Optional.empty();
		}
		return Optional.of(unread.messages().stream()
				.map(GmailResponses.MessageRef::id)
				.collect(Collectors.toUnmodifiableSet()));
	}

	/** Ids matching a search, and whether they are all of them. */
	private record Listing(List<GmailResponses.MessageRef> messages, boolean complete) {
	}

	private Listing list(String accessToken, String query, int maxPages, int limit) {
		List<GmailResponses.MessageRef> collected = new ArrayList<>();
		String pageToken = null;

		for (int page = 1; page <= maxPages; page++) {
			String current = pageToken;
			GmailResponses.MessageList body = execute(() -> client(accessToken)
					.get()
					.uri(uri -> {
						uri.path("/gmail/v1/users/me/messages")
								.queryParam("maxResults", PER_PAGE)
								.queryParam("q", query);
						if (current != null) {
							uri.queryParam("pageToken", current);
						}
						return uri.build();
					})
					.retrieve()
					.body(GmailResponses.MessageList.class), "your messages");

			if (body == null || body.messages() == null || body.messages().isEmpty()) {
				return new Listing(collected, true);
			}
			collected.addAll(body.messages());

			if (collected.size() >= limit) {
				log.info("listed Gmail up to the {}-message ceiling; the rest is read on a later sweep", limit);
				return new Listing(collected.subList(0, limit), false);
			}
			if (body.nextPageToken() == null || body.nextPageToken().isBlank()) {
				return new Listing(collected, true);
			}
			pageToken = body.nextPageToken();
		}

		// never truncate quietly: a capped sweep looks identical to a complete one otherwise
		log.warn("stopped paging Gmail at the {}-page cap with {} messages collected", maxPages, collected.size());
		return new Listing(collected, false);
	}

	/** What a mailbox has recorded happening to it, and where to resume from next time. */
	record HistorySince(List<GmailResponses.HistoryRecord> records, long historyId) {
	}

	/**
	 * The mailbox's own record of label changes since {@code startHistoryId}, which is how a message
	 * read in Gmail reaches the row here. One request whatever the mailbox holds, where comparing
	 * against every unread message would cost a page per hundred of them.
	 *
	 * <p>Empty when Gmail no longer holds history that far back. Google keeps it for about a week, so
	 * an instance that was off for longer gets that answer, and it is an answer rather than a failure:
	 * the caller starts again from where the mailbox stands now.
	 */
	Optional<HistorySince> fetchHistory(String accessToken, long startHistoryId, int maxPages) {
		List<GmailResponses.HistoryRecord> records = new ArrayList<>();
		String pageToken = null;

		for (int page = 1; page <= maxPages; page++) {
			String current = pageToken;
			Optional<GmailResponses.History> answer = executeAllowingMissing(() -> client(accessToken)
					.get()
					.uri(uri -> {
						uri.path("/gmail/v1/users/me/history")
								.queryParam("startHistoryId", startHistoryId)
								.queryParam("historyTypes", "labelAdded")
								.queryParam("historyTypes", "labelRemoved")
								.queryParam("historyTypes", "messageDeleted");
						if (current != null) {
							uri.queryParam("pageToken", current);
						}
						return uri.build();
					})
					.retrieve()
					.body(GmailResponses.History.class), "what has been read in your mailbox");

			if (answer.isEmpty()) {
				return Optional.empty();
			}
			GmailResponses.History body = answer.get();
			if (body.history() != null) {
				records.addAll(body.history());
			}
			if (body.nextPageToken() == null || body.nextPageToken().isBlank()) {
				return Optional.of(new HistorySince(records, numberOr(body.historyId(), startHistoryId)));
			}
			pageToken = body.nextPageToken();
		}

		/*
		 * never truncate quietly. history records arrive oldest first: the last one read is a valid
		 * place to resume: the rest is asked for on the next sweep rather than stepped over.
		 */
		log.warn("stopped paging Gmail history at the {}-page cap with {} record(s) read", maxPages, records.size());
		return Optional.of(new HistorySince(records, resumePoint(records, startHistoryId)));
	}

	private static long resumePoint(List<GmailResponses.HistoryRecord> records, long fallback) {
		return records.isEmpty() ? fallback : numberOr(records.getLast().id(), fallback);
	}

	private static long numberOr(String value, long fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			log.warn("Gmail sent an unreadable history id: {}", value);
			return fallback;
		}
	}

	/**
	 * One message, or null when the mailbox no longer holds it. Listing and reading are separate
	 * calls. A message can be deleted between the two, and one that has gone must not fail the
	 * whole sweep.
	 *
	 * <p>The whole message rather than its metadata, because the name of a file that came with it is
	 * on the parts of the payload and the metadata format carries no parts. The body arrives with it
	 * and is read past: only the snippet is kept.
	 */
	GmailResponses.Message fetchMessage(String accessToken, String id) {
		return executeAllowingMissing(() -> client(accessToken)
				.get()
				.uri(uri -> uri.path("/gmail/v1/users/me/messages/{id}")
						.queryParam("format", "full")
						.build(id))
				.retrieve()
				.body(GmailResponses.Message.class), "a message").orElse(null);
	}

	/*
	 * one call for many messages, which is what makes "mark all read" affordable: a mailbox can hold
	 * thousands, and one request each would be thousands of requests. google takes 1000 ids at a time,
	 * so the caller chunks.
	 */
	void setUnread(String accessToken, List<String> messageIds, boolean unread) {
		Map<String, Object> body = Map.of(
				"ids", messageIds,
				unread ? "addLabelIds" : "removeLabelIds", List.of(UNREAD_LABEL));

		execute(() -> client(accessToken)
				.post()
				.uri("/gmail/v1/users/me/messages/batchModify")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toBodilessEntity(), "the read state of your messages");
	}

	private RestClient client(String accessToken) {
		return http.builder()
				.baseUrl(config.apiBaseUrl())
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	/*
	 * the same two cases GitLabClient translates into: a credential that will never work again, and a
	 * server that might answer next time. every message names which call failed. no message here may
	 * contain the token.
	 */
	private <T> T execute(Supplier<T> call, String what) {
		try {
			return call.get();
		}
		catch (RestClientResponseException ex) {
			throw translated(ex, what);
		}
		catch (ResourceAccessException ex) {
			throw translated(ex, what);
		}
	}

	/** For a call where 404 is an answer about the mailbox rather than a failure to reach it. */
	private <T> Optional<T> executeAllowingMissing(Supplier<T> call, String what) {
		try {
			return Optional.ofNullable(call.get());
		}
		catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == 404) {
				return Optional.empty();
			}
			throw translated(ex, what);
		}
		catch (ResourceAccessException ex) {
			throw translated(ex, what);
		}
	}

	private static RuntimeException translated(RestClientResponseException ex, String what) {
		int status = ex.getStatusCode().value();
		if (status == 401 || status == 403) {
			return new SourceAuthException(
					("Google rejected the token (HTTP %d) while reading %s. Connect Gmail again, and check the "
							+ "approval has not been withdrawn.").formatted(status, what));
		}
		if (status == 429) {
			return new SourceUnavailableException(
					"Google is rate limiting Sift (HTTP 429) while reading %s.".formatted(what), ex);
		}
		return new SourceUnavailableException(
				"Google answered HTTP %d while reading %s.".formatted(status, what), ex);
	}

	private static RuntimeException translated(ResourceAccessException ex, String what) {
		return new SourceUnavailableException(
				"Could not reach Google while reading %s. %s".formatted(what, ex.getMessage()), ex);
	}
}
