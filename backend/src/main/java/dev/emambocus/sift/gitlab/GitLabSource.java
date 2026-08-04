package dev.emambocus.sift.gitlab;

import static java.util.Map.entry;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.Priority;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceAccount;
import dev.emambocus.sift.sync.SourceUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads GitLab's To-Do list, which is already scoped to the person whose token it is. That choice is
 * the whole point: project events would put back exactly the noise Sift exists to remove.
 */
@Component
public class GitLabSource implements NotificationSource {

	private static final Logger log = LoggerFactory.getLogger(GitLabSource.class);

	private static final Map<String, Priority> PRIORITY_BY_ACTION = Map.ofEntries(
			entry("assigned", Priority.HIGH),
			entry("review_requested", Priority.HIGH),
			entry("approval_required", Priority.HIGH),
			entry("directly_addressed", Priority.HIGH),
			entry("mentioned", Priority.NORMAL),
			entry("build_failed", Priority.NORMAL),
			entry("unmergeable", Priority.NORMAL),
			entry("merge_train_removed", Priority.NORMAL),
			entry("review_submitted", Priority.NORMAL),
			entry("okr_checkin_requested", Priority.NORMAL),
			entry("marked", Priority.LOW),
			entry("member_access_requested", Priority.LOW));

	private static final String UNTITLED = "Untitled";

	/** So an action GitLab adds later is reported once, not on every sweep. */
	private final Set<String> reportedUnknownActions = ConcurrentHashMap.newKeySet();

	private final GitLabClient client;
	private final GitLabParticipation participation;
	private final GitLabCommentedOn commentedOn;
	private final ObjectMapper objectMapper;
	private final int maxPages;

	GitLabSource(GitLabClient client, GitLabParticipation participation, GitLabCommentedOn commentedOn,
			ObjectMapper objectMapper, SiftProperties properties) {
		this.client = client;
		this.participation = participation;
		this.commentedOn = commentedOn;
		this.objectMapper = objectMapper;
		this.maxPages = properties.sync().maxPages();
	}

	@Override
	public SourceType id() {
		return SourceType.GITLAB;
	}

	@Override
	public SourceAccount verify(String instanceUrl, String token) {
		GitLabResponses.User user = client.fetchCurrentUser(instanceUrl, token);
		return new SourceAccount(user.username(), user.name(), user.avatarUrl(), user.webUrl());
	}

	/*
	 * Todos alone are not enough. A to-do is an event: GitLab does not always raise one, it can be
	 * dismissed, and once it is gone the merge request waiting on you is invisible. So open merge
	 * requests where you are a reviewer or the assignee are read as well, which is state and is
	 * therefore always true. A merge request already covered by a to-do is dropped, since the to-do
	 * carries who asked and when.
	 */
	@Override
	public List<IncomingItem> fetch(SourceCredential credential) {
		String instanceUrl = credential.getInstanceUrl();
		String token = credential.getAccessToken();

		// also the cheapest possible check that the token still works, before any real paging
		GitLabResponses.User me = client.fetchCurrentUser(instanceUrl, token);
		if (me.id() == null) {
			throw new SourceUnavailableException("GitLab did not say who the token belongs to.");
		}

		List<GitLabResponses.Todo> todos = client.fetchPendingTodos(instanceUrl, token, maxPages);
		Set<String> covered = todos.stream()
				.map(GitLabResponses.Todo::targetUrl)
				.filter(Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());

		List<GitLabResponses.MergeRequest> reviewing =
				client.fetchReviewRequested(instanceUrl, token, me.id(), maxPages);
		List<GitLabResponses.MergeRequest> assigned = client.fetchAssignedToMe(instanceUrl, token, maxPages);
		List<GitLabResponses.MergeRequest> authored = client.fetchAuthoredMergeRequests(instanceUrl, token, maxPages);
		List<GitLabResponses.Issue> assignedIssues = client.fetchIssues(instanceUrl, token, "assigned_to_me", maxPages);
		List<GitLabResponses.Issue> authoredIssues = client.fetchIssues(instanceUrl, token, "created_by_me", maxPages);

		Map<String, IncomingItem> items = new LinkedHashMap<>();
		for (GitLabResponses.Todo todo : todos) {
			if (todo.id() == null) {
				continue;
			}
			IncomingItem item = toIncomingItem(todo);
			items.putIfAbsent(item.sourceId(), item);
		}

		// review requests first: when a merge request is both, that is the more useful framing
		collect(items, reviewing, "mr_review_requested", covered);
		collect(items, assigned, "mr_assigned", covered);

		/*
		 * everything above is "you are wanted". this is "the thing you are part of moved", which is
		 * a different question and the one GitLab's to-do list cannot answer at all. issues are
		 * watched but never emitted as items of their own; to-dos already cover being assigned one.
		 */
		List<GitLabParticipation.Watched> watched = new ArrayList<>();
		addWatched(watched, reviewing, GitLabWatchReason.REVIEWING);
		addWatched(watched, assigned, GitLabWatchReason.INVOLVED);
		addWatched(watched, authored, GitLabWatchReason.INVOLVED);
		addWatchedIssues(watched, assignedIssues, GitLabWatchReason.INVOLVED);
		addWatchedIssues(watched, authoredIssues, GitLabWatchReason.INVOLVED);

		// and the ones no list above can find: nobody put you on them, you just replied to them
		GitLabCommentedOn.Found commented = commentedOn.discover(credential, keys(watched), maxPages);
		addWatched(watched, commented.mergeRequests(), GitLabWatchReason.COMMENTED);
		addWatchedIssues(watched, commented.issues(), GitLabWatchReason.COMMENTED);

		for (IncomingItem item : participation.collect(credential, me.id(), watched, maxPages)) {
			items.putIfAbsent(item.sourceId(), item);
		}

		return List.copyOf(items.values());
	}

	private static Set<String> keys(List<GitLabParticipation.Watched> watched) {
		return watched.stream()
				.map(GitLabParticipation.Watched::key)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static void addWatched(List<GitLabParticipation.Watched> watched,
			List<GitLabResponses.MergeRequest> mergeRequests, GitLabWatchReason reason) {

		for (GitLabResponses.MergeRequest mergeRequest : mergeRequests) {
			// without a project id and iid there is no path to read its discussions from
			if (mergeRequest.webUrl() == null || mergeRequest.projectId() == null || mergeRequest.iid() == null) {
				continue;
			}
			watched.add(new GitLabParticipation.Watched(
					GitLabResourceType.MERGE_REQUEST,
					mergeRequest.projectId(),
					mergeRequest.iid(),
					titleOr(mergeRequest.title()),
					mergeRequest.webUrl(),
					GitLabUrls.projectPath(mergeRequest.references()),
					mergeRequest.updatedAt(),
					mergeRequest.sha(),
					mergeRequest.author() == null ? null : mergeRequest.author().id(),
					mergeRequest.author() == null ? null : mergeRequest.author().name(),
					mergeRequest.author() == null ? null : mergeRequest.author().avatarUrl(),
					reason));
		}
	}

	private static void addWatchedIssues(List<GitLabParticipation.Watched> watched,
			List<GitLabResponses.Issue> issues, GitLabWatchReason reason) {

		for (GitLabResponses.Issue issue : issues) {
			if (issue.webUrl() == null || issue.projectId() == null || issue.iid() == null) {
				continue;
			}
			watched.add(new GitLabParticipation.Watched(
					GitLabResourceType.ISSUE,
					issue.projectId(),
					issue.iid(),
					titleOr(issue.title()),
					issue.webUrl(),
					GitLabUrls.projectPath(issue.references()),
					issue.updatedAt(),
					null,
					issue.author() == null ? null : issue.author().id(),
					issue.author() == null ? null : issue.author().name(),
					issue.author() == null ? null : issue.author().avatarUrl(),
					reason));
		}
	}

	/*
	 * a detail line for a merge request row, from fields already in the list response so it costs
	 * nothing. what is *new* comes from the separate thread and commit rows; this says what the
	 * merge request is like right now, and is always true rather than appearing and vanishing.
	 */
	private static String summary(GitLabResponses.MergeRequest mergeRequest) {
		List<String> parts = new ArrayList<>();
		Integer comments = mergeRequest.userNotesCount();
		if (comments != null && comments > 0) {
			parts.add(comments == 1 ? "1 comment" : comments + " comments");
		}
		if (Boolean.TRUE.equals(mergeRequest.hasConflicts())) {
			parts.add("has conflicts");
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	private static String titleOr(String title) {
		return title == null || title.isBlank() ? UNTITLED : title;
	}

	private void collect(Map<String, IncomingItem> items, List<GitLabResponses.MergeRequest> mergeRequests,
			String kind, Set<String> covered) {

		for (GitLabResponses.MergeRequest mergeRequest : mergeRequests) {
			// a draft says "not ready" outright, so it is not something waiting on anyone yet
			if (Boolean.TRUE.equals(mergeRequest.draft()) || covered.contains(mergeRequest.webUrl())) {
				continue;
			}
			if (mergeRequest.id() == null) {
				continue;
			}
			IncomingItem item = toIncomingItem(mergeRequest, kind);
			items.putIfAbsent(item.sourceId(), item);
		}
	}

	private IncomingItem toIncomingItem(GitLabResponses.MergeRequest mergeRequest, String kind) {
		return new IncomingItem(
				"mr:" + mergeRequest.id(),
				kind,
				Priority.HIGH,
				mergeRequest.title(),
				summary(mergeRequest),
				mergeRequest.author() == null ? null : mergeRequest.author().name(),
				mergeRequest.author() == null ? null : mergeRequest.author().avatarUrl(),
				GitLabUrls.projectPath(mergeRequest.references()),
				GitLabUrls.projectUrl(mergeRequest.webUrl()),
				mergeRequest.webUrl(),
				mergeRequest.createdAt(),
				mergeRequest.updatedAt() == null ? mergeRequest.createdAt() : mergeRequest.updatedAt(),
				rawPayload(mergeRequest),
				// an open merge request that stops being listed has been merged or closed
				true);
	}

	private IncomingItem toIncomingItem(GitLabResponses.Todo todo) {
		String title = title(todo);
		return new IncomingItem(
				"todo:" + todo.id(),
				todo.actionName(),
				priorityOf(todo.actionName()),
				title,
				// the todo body is very often just the target's title again
				Objects.equals(todo.body(), title) ? null : todo.body(),
				todo.author() == null ? null : todo.author().name(),
				todo.author() == null ? null : todo.author().avatarUrl(),
				contextLabel(todo),
				contextUrl(todo),
				todo.targetUrl(),
				todo.createdAt(),
				todo.updatedAt() == null ? todo.createdAt() : todo.updatedAt(),
				rawPayload(todo),
				// a to-do that stops being pending has been dealt with in GitLab
				true);
	}

	private Priority priorityOf(String actionName) {
		Priority known = PRIORITY_BY_ACTION.get(actionName);
		if (known != null) {
			return known;
		}
		/*
		 * an unrecognised action becomes NORMAL rather than LOW on purpose. this app hides things,
		 * so the failure mode of guessing wrong must be a little noise, never a silently buried item.
		 */
		if (reportedUnknownActions.add(actionName)) {
			log.info("unmapped GitLab todo action '{}', treating it as normal priority", actionName);
		}
		return Priority.NORMAL;
	}

	private static String title(GitLabResponses.Todo todo) {
		if (todo.target() != null && todo.target().title() != null && !todo.target().title().isBlank()) {
			return todo.target().title();
		}
		if (todo.body() != null && !todo.body().isBlank()) {
			return todo.body();
		}
		return UNTITLED;
	}

	// project for most todos, group for the ones raised against an epic or a group
	private static String contextLabel(GitLabResponses.Todo todo) {
		if (todo.project() != null) {
			return todo.project().pathWithNamespace();
		}
		return todo.group() == null ? null : todo.group().fullPath();
	}

	private static String contextUrl(GitLabResponses.Todo todo) {
		if (todo.project() != null) {
			return todo.project().webUrl();
		}
		return todo.group() == null ? null : todo.group().webUrl();
	}

	private String rawPayload(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JacksonException ex) {
			// the payload is only ever used for a detail view, so losing it must not fail the sync
			log.warn("could not serialise a GitLab record for storage: {}", ex.getMessage());
			return null;
		}
	}
}
