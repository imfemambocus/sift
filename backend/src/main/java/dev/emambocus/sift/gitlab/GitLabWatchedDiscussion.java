package dev.emambocus.sift.gitlab;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "gitlab_watched_discussions")
@Getter
@Setter
@NoArgsConstructor
public class GitLabWatchedDiscussion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "discussion_id", nullable = false)
	private String discussionId;

	/** The newest note already accounted for. Anything above it is what the user has not seen. */
	@Column(name = "last_note_id", nullable = false)
	private long lastNoteId;

	@Column(name = "first_seen_at", nullable = false)
	private Instant firstSeenAt;

	static GitLabWatchedDiscussion of(UUID userId, String discussionId, long lastNoteId, Instant at) {
		GitLabWatchedDiscussion discussion = new GitLabWatchedDiscussion();
		discussion.userId = userId;
		discussion.discussionId = discussionId;
		discussion.lastNoteId = lastNoteId;
		discussion.firstSeenAt = at;
		return discussion;
	}
}
