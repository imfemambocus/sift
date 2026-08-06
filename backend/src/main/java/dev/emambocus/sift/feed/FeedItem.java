package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing that wants your attention, normalised away from whichever source produced it. Every
 * field here has to make sense for a future source too, so nothing GitLab-shaped belongs in it;
 * that goes in {@code rawPayload}.
 */
@Entity
@Table(name = "feed_items")
@Getter
@Setter
@NoArgsConstructor
public class FeedItem {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SourceType source;

	/** Stable id within the source, prefixed by kind of record (for example {@code todo:4213}). */
	@Column(name = "source_id", nullable = false)
	private String sourceId;

	/** The source's own action token, e.g. {@code review_requested}. Rules match on this. */
	@Column(nullable = false)
	private String kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Priority priority;

	@Column(nullable = false)
	private String title;

	@Column
	private String body;

	@Column(name = "actor_name")
	private String actorName;

	@Column(name = "actor_avatar_url")
	private String actorAvatarUrl;

	@Column(name = "context_label")
	private String contextLabel;

	@Column(name = "context_url")
	private String contextUrl;

	@Column(nullable = false)
	private String url;

	/** What this row is about. See {@link GroupKeys}: the feed pages over these, not over items. */
	@Column(name = "group_key", nullable = false)
	private String groupKey;

	/** When the underlying thing was created. Kept for context, not for ordering. */
	@Column(name = "source_created_at", nullable = false)
	private Instant sourceCreatedAt;

	/** When it last moved. This is what the feed sorts and shows, because it is what people mean. */
	@Column(name = "activity_at", nullable = false)
	private Instant activityAt;

	@Column(name = "first_seen_at", nullable = false)
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "notified_at")
	private Instant notifiedAt;

	/*
	 * set when the item stops coming back from the source, rather than deleting the row: it keeps
	 * the history, and it stops a reappearing item being notified a second time
	 */
	@Column(name = "resolved_at")
	private Instant resolvedAt;

	/**
	 * Whether absence from a sync means "dealt with". True for state a source keeps reporting, false
	 * for an event that happened once. See {@code V5__resolve_when_absent.sql}.
	 */
	@Column(name = "resolve_when_absent", nullable = false)
	private boolean resolveWhenAbsent = true;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_payload")
	private String rawPayload;
}
