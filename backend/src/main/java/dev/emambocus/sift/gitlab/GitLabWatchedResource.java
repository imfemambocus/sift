package dev.emambocus.sift.gitlab;

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

@Entity
@Table(name = "gitlab_watched_resources")
@Getter
@Setter
@NoArgsConstructor
public class GitLabWatchedResource {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "resource_type", nullable = false)
	private GitLabResourceType resourceType;

	@Column(name = "project_id", nullable = false)
	private long projectId;

	@Column(name = "resource_iid", nullable = false)
	private long resourceIid;

	@Column(nullable = false)
	private String title;

	@Column(name = "web_url", nullable = false)
	private String webUrl;

	@Column(name = "last_updated_at")
	private Instant lastUpdatedAt;

	@Column(name = "last_sha")
	private String lastSha;

	@Column(name = "first_seen_at", nullable = false)
	private Instant firstSeenAt;

	static GitLabWatchedResource of(UUID userId, GitLabResourceType type, long projectId, long iid,
			String title, String webUrl, Instant at) {
		GitLabWatchedResource resource = new GitLabWatchedResource();
		resource.userId = userId;
		resource.resourceType = type;
		resource.projectId = projectId;
		resource.resourceIid = iid;
		resource.title = title;
		resource.webUrl = webUrl;
		resource.firstSeenAt = at;
		return resource;
	}

	String key() {
		return resourceType + ":" + projectId + ":" + resourceIid;
	}
}
