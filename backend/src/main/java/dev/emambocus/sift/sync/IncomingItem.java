package dev.emambocus.sift.sync;

import java.time.Instant;
import java.util.List;

/**
 * What a source adapter produces. Deliberately not the JPA entity: an adapter should not be able to
 * touch persistence state such as {@code firstSeenAt} or {@code resolvedAt}.
 */
public record IncomingItem(
		String sourceId,
		String kind,
		String title,
		String body,
		String actorName,
		String actorAvatarUrl,
		String contextLabel,
		String contextUrl,
		String url,
		/**
		 * What this row is about, when the source knows better than the url does. Null for a source
		 * whose urls already say it, which is what {@code GroupKeys.of} works out by stripping the
		 * fragment. Mail is the case that needs it: every message lives at the same path and only the
		 * fragment differs, so the thread id has to come from the adapter.
		 */
		String conversationId,
		Instant sourceCreatedAt,
		/**
		 * When this last moved. The feed orders and timestamps by it, so for anything long-lived
		 * (a merge request awaiting review) this must be the latest activity, not the creation date.
		 */
		Instant activityAt,
		/**
		 * True when the source itself already considers this seen. Applied only where the row is
		 * first inserted, so Sift's own read state owns it from then on. Without it, connecting a
		 * mailbox would announce every message in the window as unread.
		 */
		boolean alreadyRead,
		String rawPayload,
		/**
		 * The file names that came with it. Empty for a source that carries no files. They are part of
		 * the search haystack, so a message is found by what was attached to it as well as by what it
		 * says.
		 */
		List<String> attachments,
		/**
		 * More of the source's own text, searched and never shown. A mail body is what needs it: the
		 * snippet on the row is about a hundred characters, which is too little to find a message by
		 * something it says. Null for a source whose rows already carry all the text they have.
		 */
		String searchExtra,
		/**
		 * True when this is state the source keeps reporting, so its disappearance means it is done.
		 * False when it is an event: it happened once, and the next sync not mentioning it again
		 * says nothing at all about whether the user has dealt with it.
		 */
		boolean resolveWhenAbsent) {

	public IncomingItem {
		attachments = attachments == null ? List.of() : List.copyOf(attachments);
	}

	/** For a source whose items carry no files and no text beyond what a row shows, which is most. */
	public IncomingItem(String sourceId, String kind, String title, String body, String actorName,
			String actorAvatarUrl, String contextLabel, String contextUrl, String url,
			String conversationId, Instant sourceCreatedAt, Instant activityAt, boolean alreadyRead,
			String rawPayload, boolean resolveWhenAbsent) {

		this(sourceId, kind, title, body, actorName, actorAvatarUrl, contextLabel, contextUrl, url,
				conversationId, sourceCreatedAt, activityAt, alreadyRead, rawPayload, List.of(), null,
				resolveWhenAbsent);
	}
}
