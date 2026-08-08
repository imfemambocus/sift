package dev.emambocus.sift.gmail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How far through a mailbox Sift has read, which is what keeps a sweep to the new messages.
 *
 * <p>A mailbox is far larger than a to-do list and every message costs a request of its own, so
 * re-reading it from the top on every sweep is not affordable the way re-reading GitLab's lists is.
 * One row per user, because a person has one Gmail connection.
 */
@Entity
@Table(name = "gmail_sync_state")
@Getter
@Setter
@NoArgsConstructor
public class GmailSyncState {

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	/** The arrival time of the newest message read so far. The next sweep asks for anything after it. */
	@Column(name = "newest_message_at", nullable = false)
	private Instant newestMessageAt;

	/**
	 * The arrival time of the oldest message read so far. The next sweep asks for one chunk below it,
	 * which is what walks the mailbox back to its beginning.
	 */
	@Column(name = "oldest_message_at")
	private Instant oldestMessageAt;

	/** True once nothing older than {@link #oldestMessageAt} is left, so the walk back is over. */
	@Column(name = "backfill_done", nullable = false)
	private boolean backfillDone;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
