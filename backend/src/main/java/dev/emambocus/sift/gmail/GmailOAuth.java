package dev.emambocus.sift.gmail;

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
 * The authorization code flow against Google. Three of its details are worth knowing, because each
 * one ends the connection quietly when it is wrong.
 *
 * <p>The scope is the narrow one: {@code gmail.readonly} reads mail and nothing else, where the
 * broader Gmail scopes can also send and delete.
 *
 * <p>A refresh token arrives only with {@code access_type=offline}, and Google issues one only the
 * first time an account consents. {@code prompt=consent} forces the question every time, which is
 * what stops a reconnection producing a credential that cannot outlive its first hour.
 *
 * <p>A renewal response carries no refresh token at all, so the stored one is kept. That is Google's
 * documented behaviour rather than a guard: overwriting it with null ends the connection an hour
 * later.
 */
@Component
class GmailOAuth implements SourceOAuthFlow {

	private static final Logger log = LoggerFactory.getLogger(GmailOAuth.class);

	/** Read-only, and the narrowest Gmail scope there is. Sift never sends, labels or deletes. */
	static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

	/** Where a person reads the mail this connects, and what the settings card names. */
	static final String MAILBOX_URL = "https://mail.google.com";

	private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

	private final GmailProperties config;
	private final SourceHttp http;
	private final SourceCredentialStore store;
	private final Clock clock;

	GmailOAuth(GmailProperties config, SourceHttp http, SourceCredentialStore store, Clock clock) {
		this.config = config;
		this.http = http;
		this.store = store;
		this.clock = clock;
	}

	@Override
	public SourceType source() {
		return SourceType.GMAIL;
	}

	@Override
	public boolean configured() {
		return config.configured();
	}

	@Override
	public String target() {
		return MAILBOX_URL;
	}

	@Override
	public String accountUrl() {
		return MAILBOX_URL;
	}

	@Override
	public String authorizeUrl(String state, String codeVerifier) {
		return UriComponentsBuilder.fromUriString(config.authorizeBaseUrl())
				.path("/o/oauth2/v2/auth")
				.queryParam("client_id", config.clientId())
				.queryParam("redirect_uri", config.redirectUri())
				.queryParam("response_type", "code")
				.queryParam("scope", SCOPE)
				.queryParam("state", state)
				.queryParam("code_challenge", challengeFor(codeVerifier))
				.queryParam("code_challenge_method", "S256")
				// without both of these Google issues an access token and no way to renew it
				.queryParam("access_type", "offline")
				.queryParam("prompt", "consent")
				// without both of these Google issues an access token and no way to renew it
				.encode()
				.toUriString();
	}

	@Override
	public OAuthTokens exchange(String code, String codeVerifier) {
		MultiValueMap<String, String> form = form("authorization_code");
		form.add("code", code);
		form.add("redirect_uri", config.redirectUri());
		form.add("code_verifier", codeVerifier);
		return tokensOf(post(form, "exchanging the authorization code"), null);
	}

	/**
	 * The token to read a mailbox with, renewed first when it is close to expiring. A Google access
	 * token lives about an hour, so every sweep goes through here.
	 */
	String accessTokenFor(SourceCredential credential) {
		if (!isExpiring(credential.getExpiresAt())) {
			return credential.getAccessToken();
		}

		String refreshToken = credential.getRefreshToken();
		if (refreshToken == null) {
			throw new SourceAuthException(
					"This Gmail connection has expired and Sift has no way to renew it. Connect it again.");
		}

		MultiValueMap<String, String> form = form("refresh_token");
		form.add("refresh_token", refreshToken);
		OAuthTokens renewed = tokensOf(post(form, "renewing the access token"), refreshToken);

		// written before it is used, and applied to the caller's detached copy, as GitLab's does
		store.refreshTokens(credential, renewed.accessToken(), renewed.refreshToken(), renewed.expiresAt());
		log.debug("renewed the Gmail access token for credential {}", credential.getId());
		return credential.getAccessToken();
	}

	private OAuthTokens tokensOf(GmailResponses.OAuthToken token, String fallbackRefreshToken) {
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
	 * a refused grant is SourceAuthException and not SourceUnavailableException, because only
	 * re-authorizing fixes it and that is exactly what AUTH_FAILED records. google answers 400 for a
	 * revoked or expired refresh token. no message here may carry the secret.
	 */
	private GmailResponses.OAuthToken post(MultiValueMap<String, String> form, String what) {
		try {
			GmailResponses.OAuthToken token = http.builder()
					.baseUrl(config.tokenBaseUrl())
					.build()
					.post()
					.uri("/token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.accept(MediaType.APPLICATION_JSON)
					.body(form)
					.retrieve()
					.body(GmailResponses.OAuthToken.class);

			if (token == null || token.accessToken() == null) {
				throw new SourceUnavailableException("Google returned no access token while " + what + ".");
			}
			return token;
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status == 400 || status == 401) {
				throw new SourceAuthException(
						("Google refused the authorization (HTTP %d) while %s. Connect Gmail again. "
								+ "If this keeps happening, check the client's redirect URI and secret.")
								.formatted(status, what));
			}
			throw new SourceUnavailableException("Google answered HTTP %d while %s.".formatted(status, what), ex);
		}
		catch (ResourceAccessException ex) {
			throw new SourceUnavailableException(
					"Could not reach Google while %s. %s".formatted(what, ex.getMessage()), ex);
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
