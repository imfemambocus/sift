package dev.emambocus.sift.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.SourceAuthException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The OAuth half of the GitLab connection. Every rule here is one that only fails against a real
 * instance: the wrong header authenticates nothing, an unrenewed token dies after two hours, and a
 * renewal that is not stored ends the connection outright because GitLab invalidates the old
 * refresh token the moment it issues a new one.
 */
class GitLabOAuthTest extends SiftIntegrationTest {

	private static final String USER = """
			{"id": 42, "username": "sam", "name": "Sam", "web_url": "https://gl.example.org/sam"}
			""";

	private static final String RENEWED = """
			{"access_token": "new-access", "refresh_token": "new-refresh", "expires_in": 7200}
			""";

	private static final String TOKEN_PATH = "/oauth/token";
	private static final String USER_PATH = "/api/v4/user";
	private static final String AUTHORIZATION = "Authorization";
	private static final String PRIVATE_TOKEN = "PRIVATE-TOKEN";

	@Autowired
	private GitLabSource source;

	@Autowired
	private GitLabOAuth oauth;

	@Autowired
	private SourceCredentialRepository credentials;

	private FakeGitLab gitlab;

	@BeforeEach
	void startStub() {
		gitlab = new FakeGitLab().on(USER_PATH, USER).on(TOKEN_PATH, RENEWED);
	}

	@AfterEach
	void stopStub() {
		gitlab.close();
	}

	@Test
	@DisplayName("every read authenticates with Authorization: Bearer, never with PRIVATE-TOKEN")
	void everyReadSendsABearerToken() {
		source.fetch(oauthCredential("oauth-header@uni.lu", "live-access", inAnHour()));

		assertThat(gitlab.header(USER_PATH, AUTHORIZATION)).isEqualTo("Bearer live-access");
		// PRIVATE-TOKEN is the header for a personal access token, and GitLab refuses a grant in it
		assertThat(gitlab.header(USER_PATH, PRIVATE_TOKEN)).isNull();
	}

	@Test
	@DisplayName("a token close to expiry is renewed before the sweep reads anything with it")
	void expiringTokenIsRenewedFirst() {
		SourceCredential credential = oauthCredential("renew@uni.lu", "stale-access", justExpired());

		source.fetch(credential);

		assertThat(gitlab.hits(TOKEN_PATH)).isEqualTo(1);
		// and everything after it used the new token, not the one that was about to die
		assertThat(gitlab.header(USER_PATH, AUTHORIZATION)).isEqualTo("Bearer new-access");
	}

	@Test
	@DisplayName("the renewed pair is stored, encrypted, so the next sweep does not reuse a spent refresh token")
	void renewedPairIsStoredEncrypted() {
		SourceCredential credential = oauthCredential("store@uni.lu", "stale-access", justExpired());

		source.fetch(credential);

		SourceCredential reloaded = credentials.findById(credential.getId()).orElseThrow();
		assertThat(reloaded.getAccessToken()).isEqualTo("new-access");
		assertThat(reloaded.getRefreshToken()).isEqualTo("new-refresh");
		assertThat(reloaded.getExpiresAt()).isAfter(Instant.now());
		// the converter has to apply to a targeted update too, which only the column can show
		assertThat(rawAccessToken(credential.getId())).isNotEqualTo("new-access").isNotEmpty();
		assertThat(rawRefreshToken(credential.getId())).isNotEqualTo("new-refresh").isNotEmpty();
	}

	@Test
	@DisplayName("a token with time left is not renewed, so a sweep costs no extra request")
	void healthyTokenIsLeftAlone() {
		source.fetch(oauthCredential("healthy@uni.lu", "live-access", inAnHour()));

		assertThat(gitlab.hits(TOKEN_PATH)).isZero();
	}

	@Test
	@DisplayName("a refused renewal is a reconnect, not a retry")
	void refusedRenewalIsAnAuthFailure() {
		gitlab.failing(TOKEN_PATH, 400);
		SourceCredential credential = oauthCredential("refused@uni.lu", "stale-access", justExpired());

		assertThatThrownBy(() -> source.fetch(credential)).isInstanceOf(SourceAuthException.class);
		// nothing was read with a token that could not be renewed
		assertThat(gitlab.hits(USER_PATH)).isZero();
	}

	@Test
	@DisplayName("the authorize URL carries the S256 challenge for the verifier it was built from")
	void authorizeUrlCarriesThePkceChallenge() {
		String verifier = "a-verifier-the-controller-would-have-made";

		String url = oauth.authorizeUrl("some-state", verifier);

		assertThat(url).startsWith("https://gl.example.org/oauth/authorize")
				.contains("client_id=sift-under-test")
				.contains("scope=read_api")
				.contains("state=some-state")
				.contains("code_challenge_method=S256")
				.contains("code_challenge=" + challengeFor(verifier));
		// the secret authorizes the exchange, and it must never travel through the browser
		assertThat(url).doesNotContain("not-a-real-secret");
	}

	private static String challengeFor(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Instant justExpired() {
		return Instant.now().minus(1, ChronoUnit.MINUTES);
	}

	private static Instant inAnHour() {
		return Instant.now().plus(1, ChronoUnit.HOURS);
	}

	private SourceCredential oauthCredential(String email, String accessToken, Instant expiresAt) {
		return credentials.save(SourceCredential.oauth(newUser(email), SourceType.GITLAB, gitlab.baseUrl(),
				accessToken, "old-refresh", expiresAt, Instant.now()));
	}

	private String rawAccessToken(UUID credentialId) {
		return jdbc().queryForObject(
				"select access_token_enc from source_credentials where id = ?", String.class, credentialId);
	}

	private String rawRefreshToken(UUID credentialId) {
		return jdbc().queryForObject(
				"select refresh_token_enc from source_credentials where id = ?", String.class, credentialId);
	}
}
