package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class GitLabClient {

	private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

	private static final ParameterizedTypeReference<List<GitLabResponses.Todo>> TODO_LIST =
			new ParameterizedTypeReference<>() {
			};

	private static final ParameterizedTypeReference<List<GitLabResponses.MergeRequest>> MERGE_REQUEST_LIST =
			new ParameterizedTypeReference<>() {
			};

	private static final ParameterizedTypeReference<List<GitLabResponses.Issue>> ISSUE_LIST =
			new ParameterizedTypeReference<>() {
			};

	private static final ParameterizedTypeReference<List<GitLabResponses.Discussion>> DISCUSSION_LIST =
			new ParameterizedTypeReference<>() {
			};

	private static final int PER_PAGE = 100;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

	private final RestClient.Builder builder;

	GitLabClient(RestClient.Builder builder) {
		// a polling app must never hang on an unresponsive instance and stall the whole sweep
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.builder = builder.clone().requestFactory(requestFactory);
	}

	GitLabResponses.User fetchCurrentUser(String instanceUrl, String token) {
		return execute(() -> client(instanceUrl, token)
				.get()
				.uri("/api/v4/user")
				.retrieve()
				.body(GitLabResponses.User.class), "the current user");
	}

	List<GitLabResponses.Todo> fetchPendingTodos(String instanceUrl, String token, int maxPages) {
		return paged(client(instanceUrl, token), "/api/v4/todos", Map.of("state", "pending"),
				TODO_LIST, maxPages, "todos");
	}

	/**
	 * Merge requests where the user is a reviewer. Unlike a to-do this is state, not an event, so it
	 * is still here after the to-do has been dismissed or if none was ever raised.
	 */
	List<GitLabResponses.MergeRequest> fetchReviewRequested(String instanceUrl, String token, long userId,
			int maxPages) {
		return paged(client(instanceUrl, token), "/api/v4/merge_requests",
				Map.of("scope", "all", "state", "opened", "reviewer_id", Long.toString(userId)),
				MERGE_REQUEST_LIST, maxPages, "review-requested merge requests");
	}

	List<GitLabResponses.MergeRequest> fetchAssignedToMe(String instanceUrl, String token, int maxPages) {
		return paged(client(instanceUrl, token), "/api/v4/merge_requests",
				Map.of("scope", "assigned_to_me", "state", "opened"),
				MERGE_REQUEST_LIST, maxPages, "assigned merge requests");
	}

	List<GitLabResponses.MergeRequest> fetchAuthoredMergeRequests(String instanceUrl, String token, int maxPages) {
		return paged(client(instanceUrl, token), "/api/v4/merge_requests",
				Map.of("scope", "created_by_me", "state", "opened"),
				MERGE_REQUEST_LIST, maxPages, "your own merge requests");
	}

	List<GitLabResponses.Issue> fetchIssues(String instanceUrl, String token, String scope, int maxPages) {
		return paged(client(instanceUrl, token), "/api/v4/issues",
				Map.of("scope", scope, "state", "opened"),
				ISSUE_LIST, maxPages, "issues (" + scope + ")");
	}

	/**
	 * One merge request, for working out what became of something that left the opened lists.
	 *
	 * <p>Empty rather than an exception for 403 and 404: "it is gone, or this token can no longer see
	 * it" is an answer to that question and not a failure of the sweep.
	 */
	Optional<GitLabResponses.MergeRequest> fetchMergeRequest(String instanceUrl, String token, long projectId,
			long iid) {

		String what = "merge request %d in project %d".formatted(iid, projectId);
		return execute(() -> {
			try {
				return Optional.ofNullable(client(instanceUrl, token)
						.get()
						.uri("/api/v4/projects/{project}/merge_requests/{iid}", projectId, iid)
						.retrieve()
						.body(GitLabResponses.MergeRequest.class));
			}
			catch (RestClientResponseException ex) {
				int status = ex.getStatusCode().value();
				if (status == 403 || status == 404) {
					return Optional.<GitLabResponses.MergeRequest>empty();
				}
				// anything else is a real failure, so let the shared translation have it
				throw ex;
			}
		}, what);
	}

	/** Threads on one resource. Only called when the resource's own timestamp says something moved. */
	List<GitLabResponses.Discussion> fetchDiscussions(String instanceUrl, String token, long projectId,
			GitLabResourceType type, long iid, int maxPages) {

		String path = "/api/v4/projects/%d/%s/%d/discussions".formatted(projectId, type.pathSegment(), iid);
		return paged(client(instanceUrl, token), path, Map.of(), DISCUSSION_LIST, maxPages,
				"discussions on %s %d".formatted(type.pathSegment(), iid));
	}

	private <T> List<T> paged(RestClient client, String path, Map<String, String> params,
			ParameterizedTypeReference<List<T>> type, int maxPages, String what) {

		List<T> collected = new ArrayList<>();

		for (int page = 1; page <= maxPages; page++) {
			int current = page;
			ResponseEntity<List<T>> response = execute(() -> client
					.get()
					.uri(uri -> {
						uri.path(path).queryParam("per_page", PER_PAGE).queryParam("page", current);
						params.forEach(uri::queryParam);
						return uri.build();
					})
					.retrieve()
					.toEntity(type), what);

			List<T> body = response.getBody();
			if (body == null || body.isEmpty()) {
				return collected;
			}
			collected.addAll(body);

			String next = response.getHeaders().getFirst("x-next-page");
			if (next == null || next.isBlank()) {
				return collected;
			}
		}

		// never truncate quietly: a capped sweep looks identical to a complete one otherwise
		log.warn("stopped paging GitLab {} at the {}-page cap with {} collected; some were not read",
				what, maxPages, collected.size());
		return collected;
	}

	// the instance url arrives already validated and trailing-slash trimmed: connecting a source is
	// the only way one ever gets stored, so it is normalised there rather than in every client
	private RestClient client(String instanceUrl, String token) {
		return builder.clone()
				.baseUrl(instanceUrl)
				.defaultHeader("PRIVATE-TOKEN", token)
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	/*
	 * translates transport failures into the two cases the sweep needs to tell apart: a credential
	 * that will never work again, and an instance that might answer next time. every message names
	 * which call failed, because "404" on its own sent me looking at the instance URL when the
	 * problem was one endpoint out of three. no message here may contain the token.
	 */
	private <T> T execute(Supplier<T> call, String what) {
		try {
			return call.get();
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status == 401 || status == 403) {
				throw new SourceAuthException(
						"GitLab rejected the token (HTTP %d) while reading %s. Check it has not expired and still has the read_api scope."
								.formatted(status, what));
			}
			if (status == 404) {
				throw new SourceUnavailableException(
						("GitLab answered 404 while reading %s. If every read fails, check the instance URL points at "
								+ "the GitLab root with no /api suffix; if only this one does, the endpoint may not exist "
								+ "on this GitLab version.").formatted(what));
			}
			throw new SourceUnavailableException("GitLab answered HTTP %d while reading %s.".formatted(status, what), ex);
		}
		catch (ResourceAccessException ex) {
			throw new SourceUnavailableException("Could not reach GitLab while reading %s. %s".formatted(what, ex.getMessage()), ex);
		}
	}
}
