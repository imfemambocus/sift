package dev.emambocus.sift.gmail;

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
 * <p>How far back a mailbox is read is deliberately not among it. The whole mailbox is read, because
 * the search is the reason mail is here and a search can only find what was read.
 *
 * @param baseUrl overrides all three Google hosts at once, which is how a test points the whole
 *     source at one stand-in server. Leave it unset everywhere else.
 */
@ConfigurationProperties(prefix = "sift.gmail")
record GmailProperties(String clientId, String clientSecret, String redirectUri, String baseUrl) {

	private static final String ACCOUNTS = "https://accounts.google.com";
	private static final String OAUTH = "https://oauth2.googleapis.com";
	private static final String API = "https://gmail.googleapis.com";

	GmailProperties {
		baseUrl = trimTrailingSlashes(baseUrl);
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
