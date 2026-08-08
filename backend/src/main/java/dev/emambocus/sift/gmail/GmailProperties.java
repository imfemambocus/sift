package dev.emambocus.sift.gmail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Everything a deployment configures about Gmail. It lives here and not in {@code SiftProperties}
 * because it belongs to a source, exactly as {@code GitLabOAuthProperties} does.
 *
 * <p>The prefix is one level shallower than GitLab's {@code sift.gitlab.oauth.*}, because some of
 * this is not about the grant. There is no instance URL among it: Google is one place, so its three
 * hosts are constants rather than a value a deployment can get wrong.
 *
 * @param baseUrl overrides all three Google hosts at once, which is how a test points the whole
 *     source at one stand-in server. Leave it unset everywhere else.
 * @param window how far back the very first read goes. Later reads take everything since the newest
 *     message they have already seen, so this bounds only what connecting a mailbox pulls in.
 */
@ConfigurationProperties(prefix = "sift.gmail")
record GmailProperties(String clientId, String clientSecret, String redirectUri, String baseUrl, Duration window) {

	private static final String ACCOUNTS = "https://accounts.google.com";
	private static final String OAUTH = "https://oauth2.googleapis.com";
	private static final String API = "https://gmail.googleapis.com";

	private static final Duration DEFAULT_WINDOW = Duration.ofDays(14);

	GmailProperties {
		baseUrl = trimTrailingSlashes(baseUrl);
		window = window == null ? DEFAULT_WINDOW : window;
	}

	boolean configured() {
		return StringUtils.hasText(clientId)
				&& StringUtils.hasText(clientSecret)
				&& StringUtils.hasText(redirectUri);
	}

	String authorizeBaseUrl() {
		return StringUtils.hasText(baseUrl) ? baseUrl : ACCOUNTS;
	}

	String tokenBaseUrl() {
		return StringUtils.hasText(baseUrl) ? baseUrl : OAUTH;
	}

	String apiBaseUrl() {
		return StringUtils.hasText(baseUrl) ? baseUrl : API;
	}

	private static String trimTrailingSlashes(String url) {
		if (url == null) {
			return null;
		}
		String trimmed = url.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
