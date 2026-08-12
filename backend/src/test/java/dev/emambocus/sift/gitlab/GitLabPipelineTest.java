package dev.emambocus.sift.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.SourceFetch;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A merge request's own pipeline, which needs several sweeps to say anything: the first one only
 * learns what colour the pipeline is, and a verdict is only news against the one before it.
 */
class GitLabPipelineTest extends SiftIntegrationTest {

	private static final String USER = """
			{"id": 42, "username": "isfaaq", "name": "Isfaaq", "web_url": "https://gl.example.org/isfaaq"}
			""";

	private static final String MR_URL = "https://gl.example.org/team/web/-/merge_requests/20";
	private static final String MR_LIST = "/api/v4/merge_requests";
	private static final String MR_ONE = "/api/v4/projects/5/merge_requests/20";

	private static final String FAILED = "mr-pipeline-failed:5:20:";
	private static final String FIXED = "mr-pipeline-fixed:5:20:";

	private static final String FIRST_SEEN = "2026-08-03T09:00:00.000Z";
	private static final String MOVED = "2026-08-03T11:00:00.000Z";
	private static final String MOVED_AGAIN = "2026-08-03T13:00:00.000Z";

	@Autowired
	private GitLabSource source;

	@Autowired
	private SourceCredentialRepository credentials;

	private FakeGitLab gitlab;
	private SourceCredential credential;

	@BeforeEach
	void connect() {
		gitlab = new FakeGitLab().on("/api/v4/user", USER);
		UUID userId = newUser("pipelines@uni.lu");
		credential = credentials.save(SourceCredential.oauth(userId, SourceType.GITLAB, gitlab.baseUrl(),
				"good-token", "a-refresh-token", Instant.now().plusSeconds(7200), Instant.now()));
	}

	@AfterEach
	void stopStub() {
		gitlab.close();
	}

	@Test
	@DisplayName("the first sight of a merge request only learns the colour of its pipeline")
	void firstSightIsBaselined() {
		listed(FIRST_SEEN);
		pipeline(900, "failed");

		// red on the day you connect is not news: nobody watched it turn
		assertThat(sweep()).noneSatisfy((id, item) -> assertThat(id).startsWith("mr-pipeline"));
	}

	@Test
	@DisplayName("a pipeline that fails after Sift started watching is a row")
	void failureIsAnnounced() {
		listed(FIRST_SEEN);
		pipeline(900, "success");
		sweep();

		listed(MOVED);
		pipeline(901, "failed");
		IncomingItem item = sweep().get(FAILED + "901");

		assertThat(item).isNotNull();
		assertThat(item.kind()).isEqualTo("pipeline_failed");
		// the merge request's url, so this shares the group of everything else about it
		assertThat(item.url()).isEqualTo(MR_URL);
		// a verdict happened once, so a later sweep not repeating it says nothing
		assertThat(item.resolveWhenAbsent()).isFalse();
	}

	@Test
	@DisplayName("the same pipeline still red on a later sweep says nothing a second time")
	void redTwiceIsOneRow() {
		listed(FIRST_SEEN);
		pipeline(900, "success");
		sweep();

		listed(MOVED);
		pipeline(901, "failed");
		assertThat(sweep()).containsKey(FAILED + "901");

		// the merge request moved again, so it is read again, and the verdict has not changed
		listed(MOVED_AGAIN);
		pipeline(901, "failed");
		assertThat(sweep()).doesNotContainKey(FAILED + "901");
	}

	@Test
	@DisplayName("a pipeline back to green after a failure is a row, and green with no failure is not")
	void fixIsAnnouncedOnlyAfterAFailure() {
		listed(FIRST_SEEN);
		pipeline(900, "success");
		sweep();

		// green to green: nothing was broken, so nothing was fixed
		listed(MOVED);
		pipeline(901, "success");
		assertThat(sweep()).doesNotContainKey(FIXED + "901");

		listed(MOVED_AGAIN);
		pipeline(902, "failed");
		assertThat(sweep()).containsKey(FAILED + "902");

		listed("2026-08-03T15:00:00.000Z");
		pipeline(903, "success");
		IncomingItem item = sweep().get(FIXED + "903");

		assertThat(item).isNotNull();
		assertThat(item.kind()).isEqualTo("pipeline_fixed");
	}

	@Test
	@DisplayName("a pipeline still running is looked at again, even though the merge request has not moved")
	void aRunningPipelineIsFollowedUp() {
		listed(FIRST_SEEN);
		pipeline(900, "success");
		sweep();

		listed(MOVED);
		pipeline(901, "running");
		assertThat(sweep()).doesNotContainKey(FAILED + "901");

		/*
		 * the merge request's timestamp does not move again, and GitLab does not promise to move it
		 * when a pipeline finishes. only the pending flag can bring Sift back here.
		 */
		int asked = gitlab.hits(MR_ONE);
		listed(MOVED);
		pipeline(901, "failed");

		assertThat(sweep()).containsKey(FAILED + "901");
		assertThat(gitlab.hits(MR_ONE)).isGreaterThan(asked);
	}

	@Test
	@DisplayName("a settled pipeline on a merge request that has not moved costs no request")
	void nothingToLookAtCostsNothing() {
		listed(FIRST_SEEN);
		pipeline(900, "success");
		sweep();

		int asked = gitlab.hits(MR_ONE);
		listed(FIRST_SEEN);
		sweep();

		assertThat(gitlab.hits(MR_ONE)).isEqualTo(asked);
	}

	private void listed(String updatedAt) {
		gitlab.on(MR_LIST, """
				[{"id": 700, "iid": 20, "title": "Chart V2", "state": "opened", "draft": false,
				  "project_id": 5, "sha": "aaa111", "user_notes_count": 0, "web_url": "%s",
				  "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "%s",
				  "author": {"id": 9, "name": "Maxime"}, "references": {"full": "team/web!20"}}]
				""".formatted(MR_URL, updatedAt));
	}

	private void pipeline(long id, String status) {
		gitlab.on(MR_ONE, """
				{"id": 700, "iid": 20, "title": "Chart V2", "state": "opened", "draft": false,
				 "project_id": 5, "sha": "aaa111", "web_url": "%s",
				 "created_at": "2026-08-03T09:00:00.000Z", "updated_at": "2026-08-03T09:00:00.000Z",
				 "author": {"id": 9, "name": "Maxime"}, "references": {"full": "team/web!20"},
				 "head_pipeline": {"id": %d, "status": "%s", "ref": "chart-v2", "sha": "aaa111",
				   "web_url": "https://gl.example.org/team/web/-/pipelines/%d",
				   "updated_at": "2026-08-03T12:00:00.000Z",
				   "user": {"id": 9, "name": "Maxime"}}}
				""".formatted(MR_URL, id, status, id));
	}

	private Map<String, IncomingItem> sweep() {
		SourceFetch fetched = source.fetch(credential);
		fetched.commit().run();
		return fetched.items().stream()
				.collect(Collectors.toMap(IncomingItem::sourceId, Function.identity()));
	}
}
