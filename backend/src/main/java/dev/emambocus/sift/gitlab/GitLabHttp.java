package dev.emambocus.sift.gitlab;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * One timeout policy for every call to GitLab, the API reads and the token exchange alike. A polling
 * app must never wait on an unresponsive instance: one stalled call holds up the whole sweep.
 */
@Component
class GitLabHttp {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

	private final RestClient.Builder builder;

	GitLabHttp(RestClient.Builder builder) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.builder = builder.clone().requestFactory(requestFactory);
	}

	RestClient.Builder builder() {
		return builder.clone();
	}
}
