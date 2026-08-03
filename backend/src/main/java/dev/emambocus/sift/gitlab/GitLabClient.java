package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
				.body(GitLabResponses.User.class));
	}

	List<GitLabResponses.Todo> fetchPendingTodos(String instanceUrl, String token, int maxPages) {
		RestClient client = client(instanceUrl, token);
		List<GitLabResponses.Todo> collected = new ArrayList<>();

		for (int page = 1; page <= maxPages; page++) {
			int current = page;
			ResponseEntity<List<GitLabResponses.Todo>> response = execute(() -> client
					.get()
					.uri(uri -> uri.path("/api/v4/todos")
							.queryParam("state", "pending")
							.queryParam("per_page", PER_PAGE)
							.queryParam("page", current)
							.build())
					.retrieve()
					.toEntity(TODO_LIST));

			List<GitLabResponses.Todo> body = response.getBody();
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
		log.warn("stopped paging GitLab todos at the {}-page cap with {} collected; some were not read",
				maxPages, collected.size());
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
	 * that will never work again, and an instance that might answer next time. no message here may
	 * contain the token.
	 */
	private <T> T execute(Supplier<T> call) {
		try {
			return call.get();
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status == 401 || status == 403) {
				throw new SourceAuthException(
						"GitLab rejected the token (HTTP %d). Check it has not expired and still has the read_api scope."
								.formatted(status));
			}
			if (status == 404) {
				throw new SourceUnavailableException(
						"GitLab answered 404. Check the instance URL points at the GitLab root, with no /api suffix.");
			}
			throw new SourceUnavailableException("GitLab answered HTTP %d.".formatted(status), ex);
		}
		catch (ResourceAccessException ex) {
			throw new SourceUnavailableException("Could not reach GitLab. " + ex.getMessage(), ex);
		}
	}
}
