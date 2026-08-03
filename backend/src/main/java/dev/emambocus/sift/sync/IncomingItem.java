package dev.emambocus.sift.sync;

import dev.emambocus.sift.feed.Priority;
import java.time.Instant;

/**
 * What a source adapter produces. Deliberately not the JPA entity: an adapter should not be able to
 * touch persistence state such as {@code firstSeenAt} or {@code notifiedAt}.
 */
public record IncomingItem(
		String sourceId,
		String kind,
		Priority priority,
		String title,
		String body,
		String actorName,
		String actorAvatarUrl,
		String contextLabel,
		String contextUrl,
		String url,
		Instant sourceCreatedAt,
		String rawPayload,
		/**
		 * True when this is state the source keeps reporting, so its disappearance means it is done.
		 * False when it is an event: it happened once, and the next sync not mentioning it again
		 * says nothing at all about whether the user has dealt with it.
		 */
		boolean resolveWhenAbsent) {
}
