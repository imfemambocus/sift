package dev.emambocus.sift.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.FeedSyncStore;
import dev.emambocus.sift.sync.IncomingItem;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What the feed hands the client, and the tenancy that has to hold on every one of those paths. */
class FeedServiceTest extends SiftIntegrationTest {

	private static final Instant MONDAY = Instant.parse("2026-08-03T09:00:00Z");

	@Autowired
	private FeedService feed;

	@Autowired
	private FeedSyncStore store;

	@Test
	@DisplayName("the feed is ordered by last activity, not by when things were created")
	void orderedByActivity() {
		UUID user = newUser("order@uni.lu");
		// created first, moved most recently: it has to come out on top
		store.persist(user, SourceType.GITLAB, List.of(
				item("mr:1", MONDAY.minus(7, ChronoUnit.DAYS), MONDAY.plus(2, ChronoUnit.HOURS)),
				item("todo:2", MONDAY, MONDAY.plus(1, ChronoUnit.HOURS))));

		List<FeedItemResponse> ordered = items(user);

		assertThat(ordered).extracting(FeedItemResponse::kind).hasSize(2);
		assertThat(ordered.get(0).activityAt()).isAfter(ordered.get(1).activityAt());
	}

	@Test
	@DisplayName("a resolved item stays in the feed, flagged, because the history is not filtered")
	void resolvedStayInTheFeed() {
		UUID user = newUser("resolved@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));
		store.persist(user, SourceType.GITLAB, List.of());

		List<FeedItemResponse> all = items(user);

		assertThat(all).hasSize(1);
		assertThat(all.get(0).resolved()).isTrue();
		// it wants nothing from anyone, so it must not sit in the unread count for ever either
		assertThat(all.get(0).read()).isTrue();
	}

	@Test
	@DisplayName("the group key drops the note anchor, so several rows on one thing collapse")
	void groupKeyIgnoresTheFragment() {
		UUID user = newUser("group@uni.lu");
		String mergeRequest = "https://gl.example.org/team/web/-/merge_requests/20";
		store.persist(user, SourceType.GITLAB, List.of(
				at("mr:700", mergeRequest),
				at("thread:d1", mergeRequest + "#note_1002"),
				at("todo:9", mergeRequest + "#note_998")));

		List<FeedItemResponse> all = items(user);

		assertThat(all).extracting(FeedItemResponse::groupKey)
				.containsOnly("gitlab:" + mergeRequest);
	}

	@Test
	@DisplayName("a different resource in the same project is a different group")
	void groupKeySeparatesResources() {
		UUID user = newUser("group2@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				at("mr:700", "https://gl.example.org/team/web/-/merge_requests/20"),
				at("mr:701", "https://gl.example.org/team/web/-/merge_requests/21")));

		assertThat(items(user)).extracting(FeedItemResponse::groupKey).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("marking another tenant's item read is a not-found, not a silent success")
	void readIsScopedToTheOwner() {
		UUID mine = newUser("mine@uni.lu");
		UUID theirs = newUser("theirs@uni.lu");
		store.persist(mine, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));
		UUID itemId = items(mine).get(0).id();

		assertThatThrownBy(() -> feed.setRead(theirs, itemId, true))
				.isInstanceOf(FeedItemNotFoundException.class);

		assertThat(items(mine).get(0).read()).isFalse();
	}

	@Test
	@DisplayName("an item that does not exist at all is the same answer as one that is not yours")
	void unknownItem() {
		UUID user = newUser("unknown@uni.lu");

		assertThatThrownBy(() -> feed.setRead(user, UUID.randomUUID(), true))
				.isInstanceOf(FeedItemNotFoundException.class);
	}

	@Test
	@DisplayName("read is a timestamp, so unread is clearing it rather than a second flag")
	void unreadClearsTheTimestamp() {
		UUID user = newUser("toggle@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));
		UUID itemId = items(user).get(0).id();

		feed.setRead(user, itemId, true);
		assertThat(items(user).get(0).read()).isTrue();

		feed.setRead(user, itemId, false);
		assertThat(items(user).get(0).read()).isFalse();
	}

	@Test
	@DisplayName("marking all read clears every unread row in one statement")
	void markAllRead() {
		UUID user = newUser("all@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				item("todo:1", MONDAY, MONDAY), item("todo:2", MONDAY, MONDAY), item("todo:3", MONDAY, MONDAY)));

		assertThat(feed.markAllRead(user, null)).isEqualTo(3);
		assertThat(items(user)).allMatch(FeedItemResponse::read);
	}

	@Test
	@DisplayName("it leaves an already-read row's own timestamp alone")
	void markAllReadSkipsWhatIsAlreadyRead() {
		UUID user = newUser("already@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY), item("todo:2", MONDAY, MONDAY)));
		feed.setRead(user, items(user).get(0).id(), true);

		// only the one that was still unread is touched, which is what the row count says
		assertThat(feed.markAllRead(user, null)).isEqualTo(1);
	}

	@Test
	@DisplayName("marking all read never reaches another tenant, and stops at the named source")
	void markAllReadIsScoped() {
		UUID mine = newUser("mine2@uni.lu");
		UUID theirs = newUser("theirs2@uni.lu");
		store.persist(mine, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));
		store.persist(theirs, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));

		assertThat(feed.markAllRead(mine, SourceType.GITLAB)).isEqualTo(1);
		assertThat(items(theirs).get(0).read()).isFalse();
	}

	@Test
	@DisplayName("nothing unread is zero rows, not an error")
	void markAllReadWithNothingToDo() {
		UUID user = newUser("nothing@uni.lu");

		assertThat(feed.markAllRead(user, null)).isZero();
	}

	@Test
	@DisplayName("a row was read the moment it resolved, so mark-all-read has nothing left to do")
	void markAllReadAfterResolving() {
		UUID user = newUser("gone@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(item("todo:1", MONDAY, MONDAY)));
		store.persist(user, SourceType.GITLAB, List.of());

		assertThat(feed.markAllRead(user, null)).isZero();
		assertThat(items(user).get(0).read()).isTrue();
	}

	/** The whole feed, unnarrowed, which is what most of these assertions are about. */
	private List<FeedItemResponse> items(UUID user) {
		return feed.page(all(user)).items();
	}

	private static FeedRequest all(UUID user) {
		return new FeedRequest(user, null, FeedFilter.ALL, FeedOrder.LATEST, FeedSearch.NONE, null, 50);
	}

	private static IncomingItem item(String sourceId, Instant created, Instant activity) {
		return new IncomingItem(sourceId, "assigned", "Title", null, "A Colleague",
				null, "team/web", null, "https://gl.example.org/team/web/-/merge_requests/1",
				null, created, activity, false, null, true);
	}

	private static IncomingItem at(String sourceId, String url) {
		return new IncomingItem(sourceId, "assigned", "Title", null, null, null,
				"team/web", null, url, null, MONDAY, MONDAY, false, null, true);
	}
}
