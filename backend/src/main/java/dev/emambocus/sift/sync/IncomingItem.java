package dev.emambocus.sift.sync;

import java.time.Instant;

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
		 * True when this is state the source keeps reporting, so its disappearance means it is done.
		 * False when it is an event: it happened once, and the next sync not mentioning it again
		 * says nothing at all about whether the user has dealt with it.
		 */
		boolean resolveWhenAbsent) {
}
