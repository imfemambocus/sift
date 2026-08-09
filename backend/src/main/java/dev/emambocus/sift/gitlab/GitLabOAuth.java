package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sources.OAuthTokens;
import dev.emambocus.sift.sources.SourceCredentialStore;
import dev.emambocus.sift.sources.SourceHttp;
import dev.emambocus.sift.sources.SourceOAuthFlow;
import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The authorization code flow against one GitLab instance, which is what makes a connection scoped,
 * expiring and revocable instead of a pasted token nobody can take back.
 *
 * <p>The exchange happens here and never in the browser, which is the whole reason the app is shaped
 * as a backend-for-frontend: no access token and no refresh token ever reaches JavaScript.
 */
@Component
class GitLabOAuth implements SourceOAuthFlow {

	private static final Logger log = LoggerFactory.getLogger(GitLabOAuth.class);

	/** Read-only. Sift never writes to GitLab, and there is no narrower scope for the to-do list. */
	static final String SCOPE = "read_api";

	/*
	 * renew a little before the token actually dies, so a sweep cannot start inside the margin and
	 * then run past the expiry while it pages
	 */
	private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

	private final GitLabOAuthProperties config;
	private final SourceHttp http;
	private final SourceCredentialStore store;
	private final Clock clock;

	GitLabOAuth(GitLabOAuthProperties config, SourceHttp http, SourceCredentialStore store, Clock clock) {
		this.config = config;
		this.http = http;
		this.store = store;
		this.clock = clock;
	}

	@Override
	public SourceType source() {
		return SourceType.GITLAB;
	}

	@Override
	public boolean configured() {
		return config.configured();
	}

	@Override
	public String target() {
		return config.instanceUrl();
	}

	@Override
	public String accountUrl() {
		return config.instanceUrl();
	}

	@Override
	public String authorizeUrl(String state, String codeVerifier) {
		return UriComponentsBuilder.fromUriString(config.instanceUrl())
				.path("/oauth/authorize")
				.queryParam("client_id", config.clientId())
				.queryParam("redirect_uri", config.redirectUri())
				.queryParam("response_type", "code")
				.queryParam("scope", SCOPE)
				.queryParam("state", state)
				.queryParam("code_challenge", challengeFor(codeVerifier))
				.queryParam("code_challenge_method", "S256")
				.encode()
				.toUriString();
	}

	@Override
	public OAuthTokens exchange(String code, String codeVerifier) {
		MultiValueMap<String, String> form = form("authorization_code");
		form.add("code", code);
		form.add("redirect_uri", config.redirectUri());
		form.add("code_verifier", codeVerifier);
		return tokensOf(post(config.instanceUrl(), form, "exchanging the authorization code"), null);
	}

	/**
	 * The access to read a credential with, renewing it first when it is close to expiring. Every
	 * sweep goes through here, which is what keeps a two-hour token alive.
	 */
	GitLabAccess accessFor(SourceCredential credential) {
		if (!isExpiring(credential.getExpiresAt())) {
			return GitLabAccess.of(credential);
		}

		String refreshToken = credential.getRefreshToken();
		if (refreshToken == null) {
			throw new SourceAuthException(
					"This GitLab connection has expired and Sift has no way to renew it. Connect it again.");
		}

		MultiValueMap<String, String> form = form("refresh_token");
		form.add("refresh_token", refreshToken);
		// the credential's own instance, not the configured one: the token belongs to whoever issued it
		GitLabResponses.OAuthToken renewed =
				post(credential.getInstanceUrl(), form, "renewing the access token");
		OAuthTokens tokens = tokensOf(renewed, refreshToken);

		/*
		 * written before it is used, and both halves together: GitLab invalidates the old refresh
		 * token the moment it issues a new one, so a renewal that is not stored ends the connection.
		 */
		store.refreshTokens(credential, tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
		log.debug("renewed the GitLab access token for credential {}", credential.getId());
		return GitLabAccess.of(credential);
	}

	/**
	 * @param fallbackRefreshToken kept when the response omits one, which GitLab does not do today but
	 *     which costs nothing to survive
	 */
	private OAuthTokens tokensOf(GitLabResponses.OAuthToken token, String fallbackRefreshToken) {
		// an instance old enough to issue a token that never expires simply has nothing to renew
		Instant expiresAt = token.expiresIn() == null ? null : clock.instant().plusSeconds(token.expiresIn());
		String refreshToken = token.refreshToken() == null ? fallbackRefreshToken : token.refreshToken();
		return new OAuthTokens(token.accessToken(), refreshToken, expiresAt);
	}

	private boolean isExpiring(Instant expiresAt) {
		return expiresAt != null && !expiresAt.minus(EXPIRY_MARGIN).isAfter(clock.instant());
	}

	private MultiValueMap<String, String> form(String grantType) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", grantType);
		form.add("client_id", config.clientId());
		form.add("client_secret", config.clientSecret());
		return form;
	}

	/*
	 * the same translation GitLabClient makes, for the same reason: the sweep has to tell a
	 * credential that will never work again from an instance that might answer next time. a refused
	 * grant is the first, since only re-authorizing fixes it. no message here may carry the secret.
	 */
	/**
	 * GitLab wants the application's own credentials with the token, and it answers 200 for a token it
	 * has already forgotten. The credential's own instance, never the configured one: a token belongs
	 * to whoever issued it.
	 */
	@Override
	public void revoke(SourceCredential credential) {
		if (credential.getAccessToken() == null) {
			return;
		}

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", config.clientId());
		form.add("client_secret", config.clientSecret());
		form.add("token", credential.getAccessToken());
		http.builder()
				.baseUrl(credential.getInstanceUrl())
				.build()
				.post()
				.uri("/oauth/revoke")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
	}

	private GitLabResponses.OAuthToken post(String instanceUrl, MultiValueMap<String, String> form, String what) {
		try {
			GitLabResponses.OAuthToken token = http.builder()
					.baseUrl(instanceUrl)
					.build()
					.post()
					.uri("/oauth/token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.accept(MediaType.APPLICATION_JSON)
					.body(form)
					.retrieve()
					.body(GitLabResponses.OAuthToken.class);

			if (token == null || token.accessToken() == null) {
				throw new SourceUnavailableException("GitLab returned no access token while " + what + ".");
			}
			return token;
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status == 400 || status == 401) {
				throw new SourceAuthException(
						("GitLab refused the authorization (HTTP %d) while %s. Connect GitLab again. "
								+ "If this keeps happening, check the OAuth application's redirect URI and secret.")
								.formatted(status, what));
			}
			throw new SourceUnavailableException(
					"GitLab answered HTTP %d while %s.".formatted(status, what), ex);
		}
		catch (ResourceAccessException ex) {
			throw new SourceUnavailableException(
					"Could not reach GitLab while %s. %s".formatted(what, ex.getMessage()), ex);
		}
	}

	private static String challengeFor(String codeVerifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every JVM", ex);
		}
	}
}
