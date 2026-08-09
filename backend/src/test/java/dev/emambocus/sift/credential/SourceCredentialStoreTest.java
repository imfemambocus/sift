package dev.emambocus.sift.credential;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.sync.FeedSyncStore;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sync outcome, which is written by a targeted update rather than by saving the entity. The bug
 * that rule exists for was invisible and expensive: saving the entity rewrites the token column, so a
 * credential whose token would not decrypt wrote a null into a NOT NULL column, the transaction rolled
 * back, the outcome was never recorded, and the sweep retried the same broken credential every
 * interval forever without ever being able to say so.
 */
class SourceCredentialStoreTest extends SiftIntegrationTest {

	private static final String TOKEN = "an-access-token-worth-keeping";

	@Autowired
	private SourceCredentialRepository credentials;

	@Autowired
	private FeedSyncStore store;

	@Autowired
	private EntityManager entityManager;

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

	@Test
	@Transactional
	@DisplayName("the sources come back in one order, whatever the last sweep wrote to them")
	void sourcesAreOrdered() {
		UUID userId = newUser("ordered@uni.lu");
		// connected the other way round, so insertion order alone cannot produce the answer
		credentials.save(SourceCredential.oauth(userId, SourceType.GMAIL, "https://mail.google.com",
				TOKEN, "a-refresh-token", Instant.now().plusSeconds(7200), Instant.now()));
		SourceCredential gitlab = connect(userId);
		// every sweep writes an outcome, and an update puts that row further down the page it lives on
		store.markSuccess(gitlab.getId());

		/*
		 * a sequential scan, which is what postgres picks for a table this small once it has statistics
		 * for it, and which hands rows back in the order the heap holds them. the rail and the Home
		 * cards are drawn in this order, so without an order by they swap places on their own: after a
		 * GitLab sweep the heap says gmail, gitlab, and after a Gmail sweep it says the reverse.
		 */
		entityManager.createNativeQuery("set local enable_indexscan = off").executeUpdate();
		entityManager.createNativeQuery("set local enable_bitmapscan = off").executeUpdate();

		assertThat(credentials.findByUserIdOrderBySourceAsc(userId)).extracting(SourceCredential::getSource)
				.containsExactly(SourceType.GITLAB, SourceType.GMAIL);
	}

	private SourceCredential connect(UUID userId) {
		return credentials.save(SourceCredential.oauth(userId, SourceType.GITLAB, "https://gl.example.org",
				TOKEN, "a-refresh-token", Instant.now().plusSeconds(7200), Instant.now()));
	}

	private String rawToken(UUID credentialId) {
		return jdbc().queryForObject(
				"select access_token_enc from source_credentials where id = ?", String.class, credentialId);
	}
}
