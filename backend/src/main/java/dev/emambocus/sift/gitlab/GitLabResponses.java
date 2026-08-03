package dev.emambocus.sift.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/*
 * only the fields Sift actually uses. jackson ignores the rest by default in spring boot, and
 * @JsonProperty is used per field rather than switching the whole application to snake_case, which
 * would also rename Sift's own API responses.
 */
final class GitLabResponses {

	private GitLabResponses() {
	}

	record User(
			long id,
			String username,
			String name,
			@JsonProperty("avatar_url") String avatarUrl,
			@JsonProperty("web_url") String webUrl) {
	}

	record Project(
			long id,
			String name,
			@JsonProperty("path_with_namespace") String pathWithNamespace,
			@JsonProperty("web_url") String webUrl) {
	}

	record Group(
			long id,
			String name,
			@JsonProperty("full_path") String fullPath,
			@JsonProperty("web_url") String webUrl) {
	}

	/** Shapes differ by target type, so everything here is optional. */
	record Target(String title, Long iid, @JsonProperty("web_url") String webUrl) {
	}

	record Todo(
			long id,
			@JsonProperty("action_name") String actionName,
			@JsonProperty("target_type") String targetType,
			@JsonProperty("target_url") String targetUrl,
			String body,
			String state,
			@JsonProperty("created_at") Instant createdAt,
			User author,
			Project project,
			Group group,
			Target target) {
	}
}
