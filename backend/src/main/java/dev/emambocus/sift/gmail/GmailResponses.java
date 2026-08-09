package dev.emambocus.sift.gmail;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Only the fields Sift uses, and every number boxed.
 *
 * <p>Boxed for the reason the GitLab records are: Jackson 3 throws on a null for a primitive where
 * Jackson 2 quietly substituted zero, so one absent number in one message would abort a whole sweep.
 * Absence is handled where the value is used.
 */
final class GmailResponses {

	private GmailResponses() {
	}

	/**
	 * @param historyId where the mailbox's own record of changes stands right now, which is a 64-bit
	 *     number sent as a string
	 */
	record Profile(
			@JsonProperty("emailAddress") String emailAddress,
			@JsonProperty("historyId") String historyId) {
	}

	/** One page of what has happened to a mailbox since a given point. */
	record History(
			List<HistoryRecord> history,
			@JsonProperty("nextPageToken") String nextPageToken,
			@JsonProperty("historyId") String historyId) {
	}

	record HistoryRecord(
			String id,
			@JsonProperty("labelsAdded") List<LabelChange> labelsAdded,
			@JsonProperty("labelsRemoved") List<LabelChange> labelsRemoved,
			@JsonProperty("messagesDeleted") List<MessageChange> messagesDeleted) {
	}

	record MessageChange(MessageRef message) {
	}

	record LabelChange(MessageRef message, @JsonProperty("labelIds") List<String> labelIds) {
	}

	/** A list page. The ids are all it carries: everything else needs a call of its own. */
	record MessageList(
			List<MessageRef> messages,
			@JsonProperty("nextPageToken") String nextPageToken) {
	}

	record MessageRef(String id, @JsonProperty("threadId") String threadId) {
	}

	/**
	 * @param internalDate milliseconds since the epoch, as a string, which is how Gmail sends every
	 *     64-bit number in this API
	 */
	record Message(
			String id,
			@JsonProperty("threadId") String threadId,
			@JsonProperty("labelIds") List<String> labelIds,
			@JsonProperty("internalDate") String internalDate,
			String snippet,
			MessagePart payload) {
	}

	record MessagePart(List<Header> headers) {
	}

	record Header(String name, String value) {
	}

	record OAuthToken(
			@JsonProperty("access_token") String accessToken,
			@JsonProperty("refresh_token") String refreshToken,
			@JsonProperty("expires_in") Long expiresIn) {
	}
}
