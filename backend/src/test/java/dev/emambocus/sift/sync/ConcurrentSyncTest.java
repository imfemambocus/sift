package dev.emambocus.sift.sync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.FeedItemRepository;
import dev.emambocus.sift.gmail.FakeGmail.Msg;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One read of one credential at a time. Two at once offer the same {@code source_id} twice, which
 * violates the unique key on {@code (user_id, source, source_id)} and fails one of the two, so the
 * sweep, "check now" and the read that follows an approval all have to be kept off each other.
 *
 * <p>The stand-in Gmail is held open to catch a read while it is running, which is the only way the
 * two are ever really at once: everything else in these tests reads in a few milliseconds.
 */
class ConcurrentSyncTest extends SiftIntegrationTest {

	private static final Duration PATIENCE = Duration.ofSeconds(10);

	@Autowired
	private FeedSyncService syncService;

	@Autowired
	private SourceCredentialRepository credentials;

	@Autowired
	private FeedItemRepository items;

	@BeforeEach
	void emptyMailbox() {
		GMAIL.reset();
	}

	@Test
	@DisplayName("a second read of one credential is refused while the first is still running")
	void oneReadAtATime() throws InterruptedException {
		GMAIL.deliver(Msg.unread("m1", "t1", minutesAgo(5), "ada@uni.lu", "The one being read"));
		SourceCredential credential = connect("busy@uni.lu");

		GMAIL.hold();
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		Thread reading = new Thread(() -> {
			try {
				syncService.sync(credential);
			}
			catch (RuntimeException ex) {
				failure.set(ex);
			}
		});
		reading.start();

		try {
			awaitSyncing(credential.getId(), true);
			// the sweep arriving at a credential a manual check is already reading
			assertThat(syncService.sync(credential)).isEmpty();
		}
		finally {
			GMAIL.release();
			reading.join(PATIENCE.toMillis());
		}

		assertThat(failure.get()).isNull();
		assertThat(syncService.isSyncing(credential.getId())).isFalse();
		assertThat(items.findByUserIdAndSource(credential.getUserId(), SourceType.GMAIL)).hasSize(1);
	}

	@Test
	@DisplayName("a read started in the background claims the credential before it hands the caller back")
	void theBackgroundReadClaimsTheCredentialAtOnce() {
		GMAIL.deliver(Msg.unread("m1", "t1", minutesAgo(5), "ada@uni.lu", "The one being read"));
		SourceCredential credential = connect("landing@uni.lu");

		GMAIL.hold();
		try {
			syncService.syncInBackground(credential);

			/*
			 * no waiting here on purpose. the claim is taken on this thread rather than inside the task,
			 * so a sweep firing between the two cannot start a second read of the same credential, and a
			 * page asking right away is told the source is syncing rather than that it has never run.
			 */
			assertThat(syncService.isSyncing(credential.getId())).isTrue();
			assertThat(syncService.sync(credential)).isEmpty();
		}
		finally {
			GMAIL.release();
		}

		awaitSyncing(credential.getId(), false);
		assertThat(items.findByUserIdAndSource(credential.getUserId(), SourceType.GMAIL)).hasSize(1);
	}

	private void awaitSyncing(UUID credentialId, boolean expected) {
		Instant deadline = Instant.now().plus(PATIENCE);
		while (syncService.isSyncing(credentialId) != expected) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("the credential never reported syncing=" + expected);
			}
			pause();
		}
	}

	private static void pause() {
		try {
			Thread.sleep(20);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while waiting for a read", ex);
		}
	}

	private SourceCredential connect(String email) {
		return credentials.save(SourceCredential.oauth(newUser(email), SourceType.GMAIL,
				"https://mail.google.com", "live-access", "a-refresh-token",
				Instant.now().plus(1, ChronoUnit.HOURS), Instant.now()));
	}

	private static long minutesAgo(int minutes) {
		return Instant.now().minus(minutes, ChronoUnit.MINUTES).toEpochMilli();
	}
}
