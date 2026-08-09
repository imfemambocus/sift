package dev.emambocus.sift.gmail;

import dev.emambocus.sift.sources.SourceHttp;
import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

	/** Everything the row shows. Asking for the body would multiply the payload for no gain. */
	private static final String[] HEADERS = {"Subject", "From", "To", "Date"};

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
	 * <p>Listing is the cheap half of this API, so a caller that has to know the oldest of a set can
	 * afford to list all of it and read only the end.
	 */
	List<GmailResponses.MessageRef> listMessages(String accessToken, String query, int maxPages) {
		return listMessages(accessToken, query, maxPages, Integer.MAX_VALUE);
	}

	List<GmailResponses.MessageRef> listMessages(String accessToken, String query, int maxPages, int limit) {
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
				return collected;
			}
			collected.addAll(body.messages());

			if (collected.size() >= limit) {
				log.info("listed Gmail up to the {}-message ceiling; the rest is read on a later sweep", limit);
				return collected.subList(0, limit);
			}
			if (body.nextPageToken() == null || body.nextPageToken().isBlank()) {
				return collected;
			}
			pageToken = body.nextPageToken();
		}

		// never truncate quietly: a capped sweep looks identical to a complete one otherwise
		log.warn("stopped paging Gmail at the {}-page cap with {} messages collected", maxPages, collected.size());
		return collected;
	}

	/** One message, with its headers and labels but not its body. */
	GmailResponses.Message fetchMessage(String accessToken, String id) {
		return execute(() -> client(accessToken)
				.get()
				.uri(uri -> {
					uri.path("/gmail/v1/users/me/messages/{id}").queryParam("format", "metadata");
					for (String header : HEADERS) {
						uri.queryParam("metadataHeaders", header);
					}
					return uri.build(id);
				})
				.retrieve()
				.body(GmailResponses.Message.class), "a message");
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
			int status = ex.getStatusCode().value();
			if (status == 401 || status == 403) {
				throw new SourceAuthException(
						("Google rejected the token (HTTP %d) while reading %s. Connect Gmail again, and check the "
								+ "approval has not been withdrawn.").formatted(status, what));
			}
			if (status == 429) {
				throw new SourceUnavailableException(
						"Google is rate limiting Sift (HTTP 429) while reading %s.".formatted(what), ex);
			}
			throw new SourceUnavailableException(
					"Google answered HTTP %d while reading %s.".formatted(status, what), ex);
		}
		catch (ResourceAccessException ex) {
			throw new SourceUnavailableException(
					"Could not reach Google while reading %s. %s".formatted(what, ex.getMessage()), ex);
		}
	}
}
