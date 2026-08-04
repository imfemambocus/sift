package dev.emambocus.sift.gitlab;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of participation tracking, in its own bean for the same reason as the other
 * stores: the adapter around it makes HTTP calls, which must not happen inside a transaction.
 */
@Service
public class GitLabWatchStore {

	private final GitLabWatchedResourceRepository resources;
	private final GitLabWatchedDiscussionRepository discussions;

	GitLabWatchStore(GitLabWatchedResourceRepository resources,
			GitLabWatchedDiscussionRepository discussions) {
		this.resources = resources;
		this.discussions = discussions;
	}

	@Transactional(readOnly = true)
	public Map<String, GitLabWatchedResource> resourcesFor(UUID userId) {
		return resources.findByUserId(userId).stream()
				.collect(Collectors.toMap(GitLabWatchedResource::key, Function.identity(), (a, b) -> a));
	}

	@Transactional(readOnly = true)
	public Map<String, GitLabWatchedDiscussion> discussionsFor(UUID userId) {
		return discussions.findByUserId(userId).stream()
				.collect(Collectors.toMap(GitLabWatchedDiscussion::getDiscussionId, Function.identity(), (a, b) -> a));
	}

	@Transactional
	public void save(List<GitLabWatchedResource> changedResources,
			List<GitLabWatchedDiscussion> changedDiscussions) {
		if (!changedResources.isEmpty()) {
			resources.saveAll(changedResources);
		}
		if (!changedDiscussions.isEmpty()) {
			discussions.saveAll(changedDiscussions);
		}
	}

	/**
	 * Drops watch state for resources that have finished. Their discussion watermarks are left
	 * behind on purpose: they are keyed by discussion id, so if the resource ever comes back they
	 * stop it re-announcing threads that were read months ago.
	 */
	@Transactional
	public void forget(List<GitLabWatchedResource> finished) {
		if (!finished.isEmpty()) {
			resources.deleteAll(finished);
		}
	}
}
