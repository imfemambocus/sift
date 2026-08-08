package dev.emambocus.sift.sources;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * One timeout policy for every call Sift makes to a source, the API reads and the token exchanges
 * alike. A polling app must never wait on an unresponsive server: one stalled call holds up the
 * whole sweep, and the sweep is one credential at a time.
 */
@Component
public class SourceHttp {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

	private final RestClient.Builder builder;

	SourceHttp(RestClient.Builder builder) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.builder = builder.clone().requestFactory(requestFactory);
	}

	public RestClient.Builder builder() {
		return builder.clone();
	}
}
