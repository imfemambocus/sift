package dev.emambocus.sift.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.FeedSyncStore;
import dev.emambocus.sift.sync.IncomingItem;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Paging, narrowing and searching, all of which the server does: a browser holds one page, so none
 * of them can be answered on that side.
 */
class FeedPageTest extends SiftIntegrationTest {

	private static final Instant MONDAY = Instant.parse("2026-08-03T09:00:00Z");
	/** What a relative span such as {@code after:7d} is measured back from. */
	private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
	private static final String MR = "https://gl.example.org/team/web/-/merge_requests/";
	private static final String MAIL = "https://mail.example.org/#all/";

	@Autowired
	private FeedService feed;

	@Autowired
	private FeedSyncStore store;

	@Test
	@DisplayName("a page is a number of groups, so one thing's events are never split across it")
	void pagesOverGroups() {
		UUID user = newUser("groups@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());

		FeedPageResponse first = feed.page(page(user, 2, null));

		// two groups, both of them whole, which is four rows rather than the two an item limit gives
		assertThat(first.items()).hasSize(4);
		assertThat(first.items()).extracting(FeedItemResponse::groupKey)
				.containsOnly("gitlab:" + MR + "3", "gitlab:" + MR + "2");
		assertThat(first.nextCursor()).isNotNull();
	}

	@Test
	@DisplayName("the cursor walks every group exactly once")
	void cursorWalksTheWholeFeed() {
		UUID user = newUser("walk@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());

		List<String> seen = new ArrayList<>();
		String cursor = null;
		do {
			FeedPageResponse next = feed.page(page(user, 2, cursor));
			next.items().forEach(item -> seen.add(item.id().toString()));
			cursor = next.nextCursor();
		}
		while (cursor != null);

		assertThat(seen).hasSize(6).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("the last page says so, rather than handing out a cursor that answers nothing")
	void lastPageHasNoCursor() {
		UUID user = newUser("last@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());

		assertThat(feed.page(page(user, 3, null)).nextCursor()).isNull();
	}

	@Test
	@DisplayName("longest waiting reverses the groups and leads each one with its oldest event")
	void waitingOrder() {
		UUID user = newUser("waiting@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());

		List<FeedItemResponse> latest = feed.page(page(user, 50, null)).items();
		List<FeedItemResponse> waiting = feed.page(new FeedRequest(user, null, FeedFilter.ALL,
				FeedOrder.WAITING, FeedSearch.NONE, null, 50)).items();

		assertThat(waiting).hasSize(6);
		assertThat(waiting.getFirst().groupKey()).isEqualTo(latest.getLast().groupKey());
		// the lead of a group is its oldest event under this order, which is what dates the group
		assertThat(waiting.getFirst().activityAt()).isBefore(waiting.get(1).activityAt());
		assertThat(latest.getFirst().activityAt()).isAfter(latest.get(1).activityAt());
	}

	@Test
	@DisplayName("the unread filter narrows to unread rows and the counts agree with it")
	void unreadFilter() {
		UUID user = newUser("unread@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());
		feed.setRead(user, feed.page(page(user, 50, null)).items().getFirst().id(), true);

		List<FeedItemResponse> unread = feed.page(new FeedRequest(user, null, FeedFilter.UNREAD,
				FeedOrder.LATEST, FeedSearch.NONE, null, 50)).items();

		assertThat(unread).hasSize(5).allMatch(item -> !item.read());
		assertThat(feed.summary(user).getFirst().unread()).isEqualTo(5);
	}

	@Test
	@DisplayName("one typo per word is forgiven, including two letters swapped")
	void searchForgivesATypo() {
		UUID user = newUser("typo@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				row("mr:1", "mr_review_requested", "Chart V2: Line chart color encoding", "David",
						"team/web", MR + "1", MONDAY)));

		// a transposition, which is the typo people actually make, and two letters short of the word
		assertThat(titles(user, "encodnig")).containsExactly("Chart V2: Line chart color encoding");
		assertThat(titles(user, "reveiw")).hasSize(1);
	}

	@Test
	@DisplayName("every word has to match, and they may be typed in any order")
	void searchIsAnUnorderedConjunction() {
		UUID user = newUser("words@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				row("mr:1", "mr_assigned", "Chart V2: Line chart color encoding", "David", "team/web",
						MR + "1", MONDAY),
				row("mr:2", "mr_assigned", "Colour ramp for the map", "David", "team/web", MR + "2",
						MONDAY)));

		assertThat(titles(user, "color chart")).containsExactly("Chart V2: Line chart color encoding");
		assertThat(titles(user, "chart nowhere")).isEmpty();
	}

	@Test
	@DisplayName("the scope prefixes narrow on the project, the author and the shape of the thing")
	void searchScopes() {
		UUID user = newUser("scopes@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				row("mr:1", "mr_assigned", "A merge request", "David", "team/web", MR + "1", MONDAY),
				row("issue:2", "assigned", "An issue", "Maxime", "team/infra",
						"https://gl.example.org/team/infra/-/issues/7", MONDAY)));

		assertThat(titles(user, "project:infra")).containsExactly("An issue");
		assertThat(titles(user, "from:david")).containsExactly("A merge request");
		assertThat(titles(user, "is:mr")).containsExactly("A merge request");
		assertThat(titles(user, "is:issue")).containsExactly("An issue");
		// anything else falls through to the source's own token, so is:merged works unlisted
		assertThat(titles(user, "is:review")).isEmpty();
		assertThat(titles(user, "is:assigned")).hasSize(2);
		// every token has to match, so two of the same key narrow to nothing
		assertThat(titles(user, "project:web project:infra")).isEmpty();
	}

	@Test
	@DisplayName("a date scope narrows to a window, as a calendar date or as a span back from now")
	void searchNarrowsByDate() {
		UUID user = newUser("dates@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				row("mr:1", "mr_assigned", "Last month", "David", "team/web", MR + "1",
						Instant.parse("2026-07-01T09:00:00Z")),
				row("mr:2", "mr_assigned", "This week", "David", "team/web", MR + "2", MONDAY)));

		assertThat(titles(user, "after:2026-08-01")).containsExactly("This week");
		assertThat(titles(user, "before:2026-08-01")).containsExactly("Last month");
		// a span back from the moment it was typed, which is how "in the last week" is asked for
		assertThat(titles(user, "after:7d")).containsExactly("This week");
		assertThat(titles(user, "before:7d")).containsExactly("Last month");
		// a month back from the ninth is the ninth, so the row from the first is outside it
		assertThat(titles(user, "after:1m")).containsExactly("This week");
		assertThat(titles(user, "after:2m")).hasSize(2);
		assertThat(titles(user, "after:12h")).isEmpty();
		// the window is the latest floor and the earliest ceiling, since every token has to match
		assertThat(titles(user, "after:2026-06-01 before:2026-08-01")).containsExactly("Last month");
		assertThat(titles(user, "after:2026-08-01 before:2026-07-01")).isEmpty();
		// a date nobody can read finds nothing, rather than being dropped and finding everything
		assertThat(titles(user, "after:2026-08")).isEmpty();
	}

	@Test
	@DisplayName("a message is found by the name of the file that came with it")
	void searchFindsAttachedFiles() {
		UUID user = newUser("files@uni.lu");
		store.persist(user, SourceType.GMAIL, List.of(
				mail("msg:1", "The quarterly numbers", MAIL + "1", List.of("Q3 budget.pdf")),
				mail("msg:2", "Lunch on Thursday", MAIL + "2", List.of())));

		assertThat(titles(user, "has:attachment")).containsExactly("The quarterly numbers");
		// the names are part of the haystack, so the file finds the message that carried it
		assertThat(titles(user, "pdf")).containsExactly("The quarterly numbers");
		assertThat(titles(user, "budget.pdf")).containsExactly("The quarterly numbers");
		// and a typo in one is forgiven exactly as it is in the subject
		assertThat(titles(user, "bugdet")).containsExactly("The quarterly numbers");
		assertThat(found(user, "has:attachment").getFirst().attachments()).containsExactly("Q3 budget.pdf");
	}

	@Test
	@DisplayName("a message is found by something its text says, and never shows that text on the row")
	void searchFindsTheTextOfAMessage() {
		UUID user = newUser("bodies@uni.lu");
		store.persist(user, SourceType.GMAIL, List.of(
				said("msg:1", "Chart V2 review", MAIL + "1", "The Okabe-Ito ramp is the one to use."),
				said("msg:2", "Lunch on Thursday", MAIL + "2", "Room B, at one.")));

		assertThat(titles(user, "okabe")).containsExactly("Chart V2 review");
		// forgiven the same typo the subject and the file names are, since it is one haystack
		assertThat(titles(user, "okabi")).containsExactly("Chart V2 review");
		assertThat(titles(user, "room")).containsExactly("Lunch on Thursday");
		// the row carries the snippet and nothing more: this text exists for the search alone
		assertThat(found(user, "okabe").getFirst().body()).isNull();
	}

	@Test
	@DisplayName("asking for read and unread at once finds nothing, since every token has to match")
	void contradictoryScopes() {
		UUID user = newUser("both@uni.lu");
		store.persist(user, SourceType.GITLAB, threeGroupsOfTwo());

		assertThat(titles(user, "is:read is:unread")).isEmpty();
	}

	@Test
	@DisplayName("a search never reaches another tenant's rows")
	void searchIsScopedToTheOwner() {
		UUID mine = newUser("mine3@uni.lu");
		UUID theirs = newUser("theirs3@uni.lu");
		store.persist(theirs, SourceType.GITLAB, List.of(
				row("mr:1", "mr_assigned", "Their secret merge request", "David", "team/web", MR + "1",
						MONDAY)));

		assertThat(titles(mine, "secret")).isEmpty();
		assertThat(titles(theirs, "secret")).hasSize(1);
	}

	@Test
	@DisplayName("the summary counts the whole feed, not the page a client is holding")
	void summaryCounts() {
		UUID user = newUser("summary@uni.lu");
		store.persist(user, SourceType.GITLAB, List.of(
				row("todo:1", "assigned", "One", "David", "team/web", MR + "1", MONDAY),
				row("todo:2", "review_requested", "Two", "David", "team/web", MR + "2", MONDAY),
				row("todo:3", "assigned", "Three", "David", "team/web", MR + "3", MONDAY)));
		// the third stops being reported, which resolves it and reads it
		store.persist(user, SourceType.GITLAB, List.of(
				row("todo:1", "assigned", "One", "David", "team/web", MR + "1", MONDAY),
				row("todo:2", "review_requested", "Two", "David", "team/web", MR + "2", MONDAY)));

		FeedSummaryResponse summary = feed.summary(user).getFirst();

		assertThat(summary.source()).isEqualTo("gitlab");
		// the history is the whole of it; what is waiting is only the part the source still reports
		assertThat(summary.total()).isEqualTo(3);
		assertThat(summary.waiting()).isEqualTo(2);
		assertThat(summary.unread()).isEqualTo(2);
		assertThat(summary.waitingUnread()).isEqualTo(2);
		assertThat(summary.waitingByKind()).containsOnly(
				org.assertj.core.api.Assertions.entry("assigned", 1L),
				org.assertj.core.api.Assertions.entry("review_requested", 1L));
	}

	@Test
	@DisplayName("a cursor the feed did not hand out is a bad request, not an empty page")
	void rejectsAForgedCursor() {
		UUID user = newUser("forged@uni.lu");

		assertThatThrownBy(() -> feed.page(page(user, 50, "not-a-cursor")))
				.isInstanceOf(InvalidFeedRequestException.class);
	}

	@Test
	@DisplayName("an unknown filter or order is a bad request rather than a silent default")
	void rejectsUnknownNarrowing() {
		assertThatThrownBy(() -> FeedFilter.parse("everything")).isInstanceOf(InvalidFeedRequestException.class);
		assertThatThrownBy(() -> FeedOrder.parse("loudest")).isInstanceOf(InvalidFeedRequestException.class);
		// absent is the default, which is not the same thing as unknown
		assertThat(FeedFilter.parse(null)).isEqualTo(FeedFilter.ALL);
		assertThat(FeedOrder.parse("")).isEqualTo(FeedOrder.LATEST);
	}

	private List<String> titles(UUID user, String query) {
		return found(user, query).stream().map(FeedItemResponse::title).toList();
	}

	private List<FeedItemResponse> found(UUID user, String query) {
		FeedRequest request = new FeedRequest(user, null, FeedFilter.ALL, FeedOrder.LATEST,
				FeedSearch.parse(query, NOW), null, 50);
		return feed.page(request).items();
	}

	private static FeedRequest page(UUID user, int limit, String cursor) {
		return new FeedRequest(user, null, FeedFilter.ALL, FeedOrder.LATEST, FeedSearch.NONE,
				FeedCursor.decode(cursor), limit);
	}

	/** Three merge requests, each with a review request and a reply on it. Newest group last. */
	private static List<IncomingItem> threeGroupsOfTwo() {
		List<IncomingItem> items = new ArrayList<>();
		for (int mr = 1; mr <= 3; mr++) {
			Instant opened = MONDAY.plus(mr, ChronoUnit.HOURS);
			items.add(row("mr:" + mr, "mr_review_requested", "Merge request " + mr, "David", "team/web",
					MR + mr, opened));
			items.add(row("thread:" + mr, "new_comment", "Merge request " + mr, "Maxime", "team/web",
					MR + mr + "#note_" + mr, opened.plus(10, ChronoUnit.MINUTES)));
		}
		return items;
	}

	private static IncomingItem row(String sourceId, String kind, String title, String actor,
			String project, String url, Instant activity) {

		return new IncomingItem(sourceId, kind, title, null, actor, null, project,
				null, url, null, MONDAY, activity, false, null, true);
	}

	private static IncomingItem mail(String sourceId, String subject, String url, List<String> files) {
		return new IncomingItem(sourceId, "mail_received", subject, null, "Ada", null, "ada@uni.lu",
				null, url, null, MONDAY, MONDAY, false, null, files, null, false);
	}

	/** A message with more of its text stored for the search, which is where a mail body goes. */
	private static IncomingItem said(String sourceId, String subject, String url, String text) {
		return new IncomingItem(sourceId, "mail_received", subject, null, "Ada", null, "ada@uni.lu",
				null, url, null, MONDAY, MONDAY, false, null, List.of(), text, false);
	}
}
