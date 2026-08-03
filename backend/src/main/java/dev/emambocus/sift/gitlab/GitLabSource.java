package dev.emambocus.sift.gitlab;

import static java.util.Map.entry;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.Priority;
import dev.emambocus.sift.sync.IncomingItem;
import dev.emambocus.sift.sync.NotificationSource;
import dev.emambocus.sift.sync.SourceAccount;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	private final ObjectMapper objectMapper;
	private final int maxPages;

	GitLabSource(GitLabClient client, ObjectMapper objectMapper, SiftProperties properties) {
		this.client = client;
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

	@Override
	public List<IncomingItem> fetch(SourceCredential credential) {
		return client
				.fetchPendingTodos(credential.getInstanceUrl(), credential.getAccessToken(), maxPages)
				.stream()
				.map(this::toIncomingItem)
				.toList();
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
				rawPayload(todo));
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

	private String rawPayload(GitLabResponses.Todo todo) {
		try {
			return objectMapper.writeValueAsString(todo);
		}
		catch (JacksonException ex) {
			// the payload is only ever used for a detail view, so losing it must not fail the sync
			log.warn("could not serialise GitLab todo {} for storage: {}", todo.id(), ex.getMessage());
			return null;
		}
	}
}
