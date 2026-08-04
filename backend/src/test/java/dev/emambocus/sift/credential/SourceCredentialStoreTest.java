package dev.emambocus.sift.credential;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.sync.FeedSyncStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sync outcome, which is written by a targeted update rather than by saving the entity. The bug
 * that rule exists for was invisible and expensive: saving the entity rewrites the token column, so a
 * credential whose token would not decrypt wrote a null into a NOT NULL column, the transaction rolled
 * back, the outcome was never recorded, and the sweep retried the same broken credential every
 * interval forever without ever being able to say so.
 */
class SourceCredentialStoreTest extends SiftIntegrationTest {

	private static final String TOKEN = "glpat-a-token-worth-keeping";

	@Autowired
	private SourceCredentialRepository credentials;

	@Autowired
	private FeedSyncStore store;

	@Test
	@DisplayName("recording a failure leaves the token exactly where it was")
	void outcomeDoesNotTouchTheToken() {
		SourceCredential saved = connect(newUser("outcome@uni.lu"));

		store.markFailure(saved.getId(), SyncStatus.ERROR, "GitLab answered HTTP 500");

		SourceCredential reloaded = credentials.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getAccessToken()).isEqualTo(TOKEN);
		assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncStatus.ERROR);
		assertThat(reloaded.getLastError()).contains("500");
		assertThat(reloaded.getLastSyncAt()).isNotNull();
	}

	@Test
	@DisplayName("a success clears the error rather than leaving the last one to look current")
	void successClearsTheError() {
		SourceCredential saved = connect(newUser("recover@uni.lu"));
		store.markFailure(saved.getId(), SyncStatus.ERROR, "GitLab answered HTTP 500");

		store.markSuccess(saved.getId());

		SourceCredential reloaded = credentials.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncStatus.OK);
		assertThat(reloaded.getLastError()).isNull();
		assertThat(reloaded.getAccessToken()).isEqualTo(TOKEN);
	}

	@Test
	@DisplayName("a long provider message is abbreviated rather than overflowing the column")
	void errorsAreAbbreviated() {
		SourceCredential saved = connect(newUser("long@uni.lu"));

		store.markFailure(saved.getId(), SyncStatus.ERROR, "x".repeat(5000));

		assertThat(credentials.findById(saved.getId()).orElseThrow().getLastError()).hasSizeLessThan(1000);
	}

	@Test
	@DisplayName("the sweep skips a rejected token, since a retry will not make it work")
	void authFailedIsNotDueForSync() {
		SourceCredential broken = connect(newUser("revoked@uni.lu"));
		SourceCredential fine = connect(newUser("healthy@uni.lu"));
		store.markFailure(broken.getId(), SyncStatus.AUTH_FAILED, "GitLab rejected the token");

		assertThat(store.dueForSync()).extracting(SourceCredential::getId)
				.contains(fine.getId())
				.doesNotContain(broken.getId());
	}

	@Test
	@DisplayName("the token is encrypted at rest, so the column never holds what was pasted in")
	void tokenIsEncryptedInTheColumn() {
		SourceCredential saved = connect(newUser("crypto@uni.lu"));

		String stored = credentials.findById(saved.getId()).orElseThrow().getAccessToken();
		assertThat(stored).as("the converter decrypts on read").isEqualTo(TOKEN);
		// and what actually sits in the column is not the token, which only raw SQL can show
		assertThat(rawToken(saved.getId())).isNotEqualTo(TOKEN).isNotEmpty();
	}

	private SourceCredential connect(UUID userId) {
		return credentials.save(SourceCredential.personalAccessToken(
				userId, SourceType.GITLAB, "https://gl.example.org", TOKEN, Instant.now()));
	}

	private String rawToken(UUID credentialId) {
		return jdbc().queryForObject(
				"select access_token_enc from source_credentials where id = ?", String.class, credentialId);
	}
}
