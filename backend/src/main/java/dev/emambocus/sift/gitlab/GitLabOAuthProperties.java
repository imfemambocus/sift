package dev.emambocus.sift.gitlab;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * The OAuth application registered on one GitLab instance, which is deployment configuration rather
 * than anything a user types. It lives here and not in {@code SiftProperties} because it belongs to
 * a source: a second source with its own application would add its own record beside this one.
 *
 * <p>All four values or none. With any of them absent Sift offers the pasted-token form only, which
 * is what a deployment that has never registered an application should see.
 */
@ConfigurationProperties(prefix = "sift.gitlab.oauth")
record GitLabOAuthProperties(String instanceUrl, String clientId, String clientSecret, String redirectUri) {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
	private static final String ADVICE = "sift.gitlab.oauth.instance-url must be an http or https URL "
			+ "pointing at the instance root, for example https://gitlab.com";

	GitLabOAuthProperties {
		// the client appends paths to this, so a trailing slash would produce a double one
		instanceUrl = trimTrailingSlashes(instanceUrl);
		/*
		 * checked here so a typo fails at boot with the property named, the same bet ddl-auto:
		 * validate makes. the alternative is a connect that fails much later, in a place that looks
		 * like GitLab is down rather than like the configuration is wrong.
		 */
		requireInstanceUrl(instanceUrl);
	}

	boolean configured() {
		return StringUtils.hasText(instanceUrl)
				&& StringUtils.hasText(clientId)
				&& StringUtils.hasText(clientSecret)
				&& StringUtils.hasText(redirectUri);
	}

	/** Absent is fine, since that only means no application is configured. Present and wrong is not. */
	private static void requireInstanceUrl(String url) {
		if (!StringUtils.hasText(url)) {
			return;
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException(ADVICE, ex);
		}
		if (uri.getHost() == null || !ALLOWED_SCHEMES.contains(uri.getScheme())) {
			throw new IllegalArgumentException(ADVICE);
		}
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
