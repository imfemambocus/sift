package dev.emambocus.sift.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.Priority;
import dev.emambocus.sift.sync.IncomingItem;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The adapter's de-duplications, which are the other place silent wrongness hurts: getting one wrong
 * shows the same merge request twice, or takes a whole sync down on a unique-key violation mid-flush.
 */
class GitLabSourceTest extends SiftIntegrationTest {

	private static final String USER = """
			{"id": 42, "username": "isfaaq", "name": "Isfaaq", "web_url": "https://gl.example.org/isfaaq"}
			""";

	private static final String MR_20 = "https://gl.example.org/team/web/-/merge_requests/20";
	private static final String MR_21 = "https://gl.example.org/team/web/-/merge_requests/21";

	@Autowired
	private GitLabSource source;

	@Autowired
	private SourceCredentialRepository credentials;

	private FakeGitLab gitlab;

	@AfterEach
	void stopStub() {
		if (gitlab != null) {
			gitlab.close();
		}
	}

	@Test
	@DisplayName("a merge request already covered by a to-do is dropped, so it is not listed twice")
	void todoWinsOverTheMergeRequest() {
		gitlab = stub()
				.on("/api/v4/todos", """
						[{"id": 1, "action_name": "review_requested", "target_url": "%s",
						  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
						  "author": {"id": 9, "name": "Maxime"},
						  "project": {"id": 5, "path_with_namespace": "team/web"},
						  "target": {"title": "Chart V2"}}]
						""".formatted(MR_20))
				.on("/api/v4/merge_requests", mergeRequest(700, 20, "Chart V2", MR_20));

		Map<String, IncomingItem> items = fetch();

		// the to-do wins because it carries who asked and when
		assertThat(items).containsKey("todo:1").doesNotContainKey("mr:700");
	}

	@Test
	@DisplayName("a merge request both assigned and awaiting review is one item, framed as review")
	void reviewFramingWins() {
		// the same list answers every merge_requests scope here, which is exactly the collision
		gitlab = stub().on("/api/v4/merge_requests", mergeRequest(700, 20, "Chart V2", MR_20));

		Map<String, IncomingItem> items = fetch();

		assertThat(items).hasSize(1);
		assertThat(items.get("mr:700").kind()).isEqualTo("mr_review_requested");
		assertThat(items.get("mr:700").priority()).isEqualTo(Priority.HIGH);
	}

	@Test
	@DisplayName("a draft says nobody is waiting, so it is skipped")
	void draftsAreSkipped() {
		gitlab = stub().on("/api/v4/merge_requests", """
				[{"id": 703, "iid": 23, "title": "Draft, not ready", "state": "opened", "draft": true,
				  "project_id": 5, "sha": "aaa", "web_url": "%s",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				  "author": {"id": 9, "name": "Maxime"}, "references": {"full": "team/web!23"}}]
				""".formatted(MR_21));

		assertThat(fetch()).isEmpty();
	}

	@Test
	@DisplayName("the source id is prefixed by record type, so a todo and an MR cannot collide")
	void sourceIdsArePrefixed() {
		gitlab = stub()
				.on("/api/v4/todos", """
						[{"id": 700, "action_name": "assigned", "target_url": "https://gl.example.org/other",
						  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
						  "project": {"id": 5, "path_with_namespace": "team/web"},
						  "target": {"title": "An issue with the same id"}}]
						""")
				.on("/api/v4/merge_requests", mergeRequest(700, 20, "Chart V2", MR_20));

		assertThat(fetch()).containsOnlyKeys("todo:700", "mr:700");
	}

	@Test
	@DisplayName("a group-level todo with no project falls back to the group path, and to the body for a title")
	void nullProjectAndTarget() {
		gitlab = stub().on("/api/v4/todos", """
				[{"id": 2, "action_name": "mentioned", "target_url": "https://gl.example.org/groups/team/-/epics/3",
				  "body": "Mentioned you on an epic",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				  "group": {"id": 8, "full_path": "team", "web_url": "https://gl.example.org/groups/team"}}]
				""");

		IncomingItem item = fetch().get("todo:2");

		assertThat(item.contextLabel()).isEqualTo("team");
		assertThat(item.title()).isEqualTo("Mentioned you on an epic");
		// the body is not repeated underneath a title it is identical to
		assertThat(item.body()).isNull();
	}

	@Test
	@DisplayName("an unrecognised action is normal, never low: this app hides things")
	void unknownActionIsNormal() {
		gitlab = stub().on("/api/v4/todos", """
				[{"id": 3, "action_name": "something_gitlab_added_later",
				  "target_url": "https://gl.example.org/x",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				  "target": {"title": "New kind of thing"}}]
				""");

		assertThat(fetch().get("todo:3").priority()).isEqualTo(Priority.NORMAL);
	}

	@Test
	@DisplayName("a merge request row takes its activity from updated_at, not from when it was opened")
	void activityFollowsUpdatedAt() {
		gitlab = stub().on("/api/v4/merge_requests", """
				[{"id": 700, "iid": 20, "title": "Opened last week, commits this morning", "state": "opened",
				  "draft": false, "project_id": 5, "sha": "aaa", "user_notes_count": 3, "web_url": "%s",
				  "created_at": "2026-07-27T09:00:00.000Z", "updated_at": "2026-08-03T11:00:00.000Z",
				  "author": {"id": 9, "name": "Maxime"}, "references": {"full": "team/web!20"}}]
				""".formatted(MR_20));

		IncomingItem item = fetch().get("mr:700");

		assertThat(item.sourceCreatedAt()).isEqualTo("2026-07-27T09:00:00Z");
		assertThat(item.activityAt()).isEqualTo("2026-08-03T11:00:00Z");
		assertThat(item.body()).isEqualTo("3 comments");
	}

	@Test
	@DisplayName("a to-do is state, so its absence next sweep does mean it was dealt with")
	void resolveWhenAbsentPerKind() {
		gitlab = stub().on("/api/v4/todos", """
				[{"id": 4, "action_name": "assigned", "target_url": "https://gl.example.org/x",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				  "target": {"title": "Assigned"}}]
				""");

		assertThat(fetch().get("todo:4").resolveWhenAbsent()).isTrue();
	}

	@Test
	@DisplayName("the activity feed failing loses only the comment-only discovery, not the sweep")
	void eventsFailureIsSurvivable() {
		gitlab = stub()
				.on("/api/v4/todos", """
						[{"id": 5, "action_name": "assigned", "target_url": "https://gl.example.org/x",
						  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
						  "target": {"title": "Still delivered"}}]
						""")
				.failing("/api/v4/events", 500);

		// the to-dos were already read by then, so they must survive it
		Map<String, IncomingItem> items = fetch();
		assertThat(items).containsKey("todo:5");
		assertThat(gitlab.hits("/api/v4/events")).isPositive();
	}

	private FakeGitLab stub() {
		return new FakeGitLab().on("/api/v4/user", USER);
	}

	private static String mergeRequest(long id, long iid, String title, String webUrl) {
		return """
				[{"id": %d, "iid": %d, "title": "%s", "state": "opened", "draft": false,
				  "project_id": 5, "sha": "aaa111", "user_notes_count": 0, "web_url": "%s",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				  "author": {"id": 9, "name": "Maxime"}, "references": {"full": "team/web!%d"}}]
				""".formatted(id, iid, title, webUrl, iid);
	}

	private Map<String, IncomingItem> fetch() {
		UUID userId = newUser("gitlab@uni.lu");
		// well clear of the expiry margin, so nothing here is about renewing: that is GitLabOAuthTest
		SourceCredential credential = credentials.save(SourceCredential.oauth(userId, SourceType.GITLAB,
				gitlab.baseUrl(), "good-token", "a-refresh-token", Instant.now().plusSeconds(7200), Instant.now()));

		return source.fetch(credential).stream()
				.collect(Collectors.toMap(IncomingItem::sourceId, Function.identity()));
	}
}
