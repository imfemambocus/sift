package dev.emambocus.sift.sync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.FeedItem;
import dev.emambocus.sift.feed.FeedItemRepository;
import dev.emambocus.sift.feed.FeedService;
import dev.emambocus.sift.feed.Priority;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The diffing rules, which is where silent wrongness would actually hurt: an item that should have
 * been resolved and was not is invisible, and one that was resolved and should not have been is
 * something the user never sees again.
 */
class FeedSyncStoreTest extends SiftIntegrationTest {

	private static final Instant MONDAY = Instant.parse("2026-08-03T09:00:00Z");

	@Autowired
	private FeedSyncStore store;

	@Autowired
	private FeedItemRepository items;

	// through the service, because updateReadAt is @Modifying and needs a transaction around it
	@Autowired
	private FeedService feed;

	@Test
	@DisplayName("state that stops being reported is resolved, an event that does is left alone")
	void resolveWhenAbsent() {
		UUID user = newUser("state@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				item("todo:1", "assigned", MONDAY, true),
				item("thread:abc", "new_comment", MONDAY, false)));

		FeedSyncStore.SyncOutcome second = store.persist(user, SourceType.GITLAB, List.of());

		assertThat(second.resolved()).isEqualTo(1);
		assertThat(resolvedAt(user, "todo:1")).isNotNull();
		// a reply arrived once; the next sweep not mentioning it says nothing about whether it was read
		assertThat(resolvedAt(user, "thread:abc")).isNull();
	}

	@Test
	@DisplayName("resolving reads a row nobody opened, so finished work leaves the unread count")
	void resolvingReadsWhatWasNeverOpened() {
		UUID user = newUser("finished@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));
		assertThat(find(user, "todo:1").getReadAt()).isNull();

		store.persist(user, SourceType.GITLAB, List.of());

		assertThat(find(user, "todo:1").getReadAt()).isNotNull();
	}

	@Test
	@DisplayName("resolving keeps the read time a row already had")
	void resolvingKeepsAnEarlierReadTime() {
		UUID user = newUser("finished2@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));
		feed.setRead(user, find(user, "todo:1").getId(), true);
		Instant read = find(user, "todo:1").getReadAt();

		store.persist(user, SourceType.GITLAB, List.of());

		assertThat(find(user, "todo:1").getReadAt()).isEqualTo(read);
	}

	@Test
	@DisplayName("an item that comes back is live again, and keeps the day it was first seen")
	void reappearing() {
		UUID user = newUser("back@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));
		Instant firstSeen = find(user, "todo:1").getFirstSeenAt();

		store.persist(user, SourceType.GITLAB, List.of());
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));

		FeedItem back = find(user, "todo:1");
		assertThat(back.getResolvedAt()).isNull();
		assertThat(back.getFirstSeenAt()).isEqualTo(firstSeen);
	}

	@Test
	@DisplayName("two items with the same source id collapse instead of breaking the unique key")
	void duplicateSourceIdsCollapse() {
		UUID user = newUser("dupe@uni.lu");

		// a merge request read from both the review-requested and the assigned endpoint
		FeedSyncStore.SyncOutcome outcome = store.persist(user, SourceType.GITLAB, List.of(
				item("mr:700", "mr_review_requested", MONDAY, true),
				item("mr:700", "mr_assigned", MONDAY, true)));

		assertThat(outcome.fetched()).isEqualTo(1);
		assertThat(outcome.added()).isEqualTo(1);
		// collected first wins, which is why review-requested is the framing that survives
		assertThat(find(user, "mr:700").getKind()).isEqualTo("mr_review_requested");
	}

	@Test
	@DisplayName("activity moving forward un-reads a row, and an equal or older timestamp does not")
	void activityClearsRead() {
		UUID user = newUser("read@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("mr:700", "mr_assigned", MONDAY, true)));
		feed.setRead(user, find(user, "mr:700").getId(), true);
		assertThat(find(user, "mr:700").getReadAt()).isNotNull();

		Instant later = MONDAY.plus(1, ChronoUnit.HOURS);
		store.persist(user, SourceType.GITLAB, List.of(item("mr:700", "mr_assigned", later, true)));
		assertThat(find(user, "mr:700").getReadAt()).as("something that moved again is new again").isNull();

		feed.setRead(user, find(user, "mr:700").getId(), true);
		Instant earlier = MONDAY.minus(1, ChronoUnit.HOURS);
		store.persist(user, SourceType.GITLAB, List.of(item("mr:700", "mr_assigned", earlier, true)));
		assertThat(find(user, "mr:700").getReadAt())
				.as("a source reporting an older timestamp must not un-read it every sweep")
				.isNotNull();
	}

	@Test
	@DisplayName("two users legitimately hold a row for the same merge request")
	void tenancy() {
		UUID one = newUser("one@uni.lu");
		UUID two = newUser("two@uni.lu");

		store.persist(one, SourceType.GITLAB, List.of(item("mr:700", "mr_review_requested", MONDAY, true)));
		store.persist(two, SourceType.GITLAB, List.of(item("mr:700", "mr_assigned", MONDAY, true)));

		assertThat(find(one, "mr:700").getKind()).isEqualTo("mr_review_requested");
		assertThat(find(two, "mr:700").getKind()).isEqualTo("mr_assigned");
	}

	@Test
	@DisplayName("one user's sweep never resolves another's items")
	void sweepIsScopedToOneUser() {
		UUID mine = newUser("mine@uni.lu");
		UUID theirs = newUser("theirs@uni.lu");
		store.persist(mine, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));
		store.persist(theirs, SourceType.GITLAB, List.of(item("todo:1", "assigned", MONDAY, true)));

		store.persist(mine, SourceType.GITLAB, List.of());

		assertThat(resolvedAt(mine, "todo:1")).isNotNull();
		assertThat(resolvedAt(theirs, "todo:1")).isNull();
	}

	@Test
	@DisplayName("an absent timestamp degrades rather than failing the sync, since both columns are NOT NULL")
	void missingTimestamps() {
		UUID user = newUser("notime@uni.lu");
		IncomingItem noTimes = new IncomingItem("todo:9", "assigned", Priority.HIGH, "No timestamps",
				null, null, null, null, null, "https://gl.example.org/x", null, null, null, true);

		store.persist(user, SourceType.GITLAB, List.of(noTimes));

		FeedItem stored = find(user, "todo:9");
		assertThat(stored.getSourceCreatedAt()).isNotNull();
		assertThat(stored.getActivityAt()).isEqualTo(stored.getSourceCreatedAt());
	}

	@Test
	@DisplayName("raw_payload round-trips through the jsonb column")
	void jsonbPayload() {
		UUID user = newUser("json@uni.lu");
		IncomingItem withPayload = new IncomingItem("todo:5", "mentioned", Priority.NORMAL, "Payload",
				null, null, null, null, null, "https://gl.example.org/x", MONDAY, MONDAY,
				"{\"action_name\":\"mentioned\",\"id\":5}", true);

		store.persist(user, SourceType.GITLAB, List.of(withPayload));

		assertThat(find(user, "todo:5").getRawPayload()).contains("\"action_name\"");
	}

	private static IncomingItem item(String sourceId, String kind, Instant activityAt, boolean resolveWhenAbsent) {
		return new IncomingItem(sourceId, kind, Priority.NORMAL, "Title for " + sourceId, null,
				"A Colleague", null, "team/web", "https://gl.example.org/team/web",
				"https://gl.example.org/team/web/-/merge_requests/1", MONDAY, activityAt, null,
				resolveWhenAbsent);
	}

	private FeedItem find(UUID userId, String sourceId) {
		return items.findByUserIdAndSource(userId, SourceType.GITLAB).stream()
				.filter(stored -> stored.getSourceId().equals(sourceId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no item " + sourceId + " for user " + userId));
	}

	private Instant resolvedAt(UUID userId, String sourceId) {
		return find(userId, sourceId).getResolvedAt();
	}
}
