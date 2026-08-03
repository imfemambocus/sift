package dev.emambocus.sift.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/*
 * only the fields Sift actually uses. jackson ignores the rest by default in spring boot, and
 * @JsonProperty is used per field rather than switching the whole application to snake_case, which
 * would also rename Sift's own API responses.
 *
 * every number and flag is boxed on purpose. jackson 3 fails on a null for a primitive where
 * jackson 2 quietly used zero, so one absent field would otherwise abort an entire sync. absence is
 * handled where the value is used instead.
 */
final class GitLabResponses {

	private GitLabResponses() {
	}

	record User(
			Long id,
			String username,
			String name,
			@JsonProperty("avatar_url") String avatarUrl,
			@JsonProperty("web_url") String webUrl) {
	}

	record Project(
			Long id,
			String name,
			@JsonProperty("path_with_namespace") String pathWithNamespace,
			@JsonProperty("web_url") String webUrl) {
	}

	record Group(
			Long id,
			String name,
			@JsonProperty("full_path") String fullPath,
			@JsonProperty("web_url") String webUrl) {
	}

	/** Shapes differ by target type, so everything here is optional. */
	record Target(String title, Long iid, @JsonProperty("web_url") String webUrl) {
	}

	/** {@code full} looks like {@code group/project!12}, the only place the project path appears. */
	record References(String full) {
	}

	record MergeRequest(
			Long id,
			Long iid,
			String title,
			String state,
			Boolean draft,
			String sha,
			@JsonProperty("user_notes_count") Integer userNotesCount,
			@JsonProperty("has_conflicts") Boolean hasConflicts,
			@JsonProperty("project_id") Long projectId,
			@JsonProperty("web_url") String webUrl,
			@JsonProperty("created_at") Instant createdAt,
			@JsonProperty("updated_at") Instant updatedAt,
			User author,
			References references) {
	}

	record Issue(
			Long id,
			Long iid,
			String title,
			String state,
			@JsonProperty("project_id") Long projectId,
			@JsonProperty("web_url") String webUrl,
			@JsonProperty("created_at") Instant createdAt,
			@JsonProperty("updated_at") Instant updatedAt,
			User author,
			References references) {
	}

	/** A note with {@code system: true} is GitLab narrating itself, not a person commenting. */
	record Note(
			Long id,
			String body,
			Boolean system,
			@JsonProperty("created_at") Instant createdAt,
			User author) {
	}

	record Discussion(String id, java.util.List<Note> notes) {
	}

	record Todo(
			Long id,
			@JsonProperty("action_name") String actionName,
			@JsonProperty("target_type") String targetType,
			@JsonProperty("target_url") String targetUrl,
			String body,
			String state,
			@JsonProperty("created_at") Instant createdAt,
			@JsonProperty("updated_at") Instant updatedAt,
			User author,
			Project project,
			Group group,
			Target target) {
	}
}
