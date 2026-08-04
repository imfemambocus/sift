package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.feed.Priority;
import dev.emambocus.sift.sync.IncomingItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "Something moved on a thing I am part of", which GitLab's to-do list does not cover at all:
 * participation is handled by its Participate email level, which is exactly the traffic Sift exists
 * to take out of a mailbox.
 *
 * <p>One row per thread, never one per reply. Twelve answers in a discussion is one thing to look at,
 * and twelve rows would rebuild the flood.
 */
@Component
class GitLabParticipation {

	private static final Logger log = LoggerFactory.getLogger(GitLabParticipation.class);

	private static final int SNIPPET_LENGTH = 180;

	/** A resource worth watching, flattened out of whichever list it was found in. */
	record Watched(
			GitLabResourceType type,
			long projectId,
			long iid,
			String title,
			String webUrl,
			String projectPath,
			Instant updatedAt,
			String sha,
			Long authorId,
			String authorName,
			String authorAvatarUrl,
			GitLabWatchReason reason) {

		String key() {
			return key(type, projectId, iid);
		}

		/** Also how a caller asks whether something is watched before spending a request on it. */
		static String key(GitLabResourceType type, long projectId, long iid) {
			return type + ":" + projectId + ":" + iid;
		}
	}

	private final GitLabClient client;
	private final GitLabWatchStore store;
	private final Clock clock;

	GitLabParticipation(GitLabClient client, GitLabWatchStore store, Clock clock) {
		this.client = client;
		this.store = store;
		this.clock = clock;
	}

	List<IncomingItem> collect(SourceCredential credential, Long selfId, List<Watched> watched, int maxPages) {
		UUID userId = credential.getUserId();
		Instant now = clock.instant();

		Map<String, GitLabWatchedResource> knownResources = store.resourcesFor(userId);
		Map<String, GitLabWatchedDiscussion> knownThreads = store.discussionsFor(userId);

		List<IncomingItem> items = new ArrayList<>();
		List<GitLabWatchedResource> resourceUpdates = new ArrayList<>();
		List<GitLabWatchedDiscussion> threadUpdates = new ArrayList<>();
		Set<String> stillListed = new HashSet<>();

		for (Watched resource : deduplicate(watched)) {
			stillListed.add(resource.key());
			GitLabWatchedResource known = knownResources.get(resource.key());
			boolean firstSight = known == null;

			if (firstSight) {
				known = GitLabWatchedResource.of(userId, resource.type(), resource.projectId(),
						resource.iid(), resource.title(), resource.webUrl(), now);
			}
			else if (announcesPush(resource, known, selfId)) {
				items.add(changesPushed(resource, now));
			}

			if (hasMoved(resource, known, firstSight)) {
				readThreads(credential, selfId, resource, firstSight, knownThreads, items, threadUpdates, now, maxPages);
			}

			known.setTitle(resource.title());
			known.setWebUrl(resource.webUrl());
			known.setLastUpdatedAt(resource.updatedAt());
			known.setLastSha(resource.sha());
			resourceUpdates.add(known);
		}

		List<GitLabWatchedResource> finished = new ArrayList<>();
		for (GitLabWatchedResource known : knownResources.values()) {
			if (stillListed.contains(known.key())) {
				continue;
			}
			settle(credential, known, items, finished, now);
		}

		store.save(resourceUpdates, threadUpdates);
		store.forget(finished);
		return items;
	}

	/*
	 * a merge request that has left the opened lists has either been merged, been closed, or stopped
	 * involving you. only the first is news, and the answer needs one request, which is why it is
	 * asked here rather than guessed from the absence.
	 */
	private void settle(SourceCredential credential, GitLabWatchedResource known, List<IncomingItem> items,
			List<GitLabWatchedResource> finished, Instant now) {

		if (known.getResourceType() != GitLabResourceType.MERGE_REQUEST) {
			finished.add(known);
			return;
		}

		Optional<GitLabResponses.MergeRequest> found;
		try {
			found = client.fetchMergeRequest(credential.getInstanceUrl(), credential.getAccessToken(),
					known.getProjectId(), known.getResourceIid());
		}
		catch (RuntimeException ex) {
			// as with an unreadable thread: report it, leave the watch row alone, and try again later
			log.warn("could not read merge request {} in project {}: {}",
					known.getResourceIid(), known.getProjectId(), ex.getMessage());
			return;
		}

		found.filter(GitLabResponses.MergeRequest::isMerged)
				.ifPresent(mergeRequest -> items.add(merged(known, mergeRequest, now)));

		// gone, closed, merged or no longer yours: either way there is nothing left to watch
		finished.add(known);
	}

	private void readThreads(SourceCredential credential, Long selfId, Watched resource, boolean firstSight,
			Map<String, GitLabWatchedDiscussion> knownThreads, List<IncomingItem> items,
			List<GitLabWatchedDiscussion> threadUpdates, Instant now, int maxPages) {

		List<GitLabResponses.Discussion> discussions;
		try {
			discussions = client.fetchDiscussions(credential.getInstanceUrl(), credential.getAccessToken(),
					resource.projectId(), resource.type(), resource.iid(), maxPages);
		}
		catch (RuntimeException ex) {
			/*
			 * one unreadable resource must not lose the whole sweep, including the to-dos already
			 * fetched. it is reported and skipped, and its stored timestamp is not advanced, so the
			 * next sweep tries again.
			 */
			log.warn("could not read discussions on {} {} in project {}: {}",
					resource.type(), resource.iid(), resource.projectId(), ex.getMessage());
			return;
		}

		for (GitLabResponses.Discussion discussion : discussions) {
			if (discussion.id() == null || discussion.notes() == null || discussion.notes().isEmpty()) {
				continue;
			}

			// a note with no id cannot be compared against a watermark, so it is not usable at all
			List<GitLabResponses.Note> notes = discussion.notes().stream()
					.filter(note -> note.id() != null)
					.toList();
			if (notes.isEmpty()) {
				continue;
			}

			GitLabWatchedDiscussion knownThread = knownThreads.get(discussion.id());
			long alreadySeen = knownThread == null ? 0 : knownThread.getLastNoteId();
			long newest = notes.stream().mapToLong(GitLabResponses.Note::id).max().orElse(0);

			List<GitLabResponses.Note> unseen = notes.stream()
					.filter(note -> note.id() > alreadySeen)
					.filter(note -> !Boolean.TRUE.equals(note.system()))
					.filter(note -> note.author() == null || !Objects.equals(note.author().id(), selfId))
					.toList();

			/*
			 * a resource seen for the first time is only baselined. without this, connecting an
			 * account would emit a row for every thread the user has ever been in.
			 */
			if (!firstSight && !unseen.isEmpty()) {
				String kind = knownThread == null ? "new_thread" : "new_comment";
				items.add(thread(resource, discussion, unseen, kind));
			}

			if (knownThread == null) {
				threadUpdates.add(GitLabWatchedDiscussion.of(credential.getUserId(), discussion.id(), newest, now));
			}
			else if (newest > alreadySeen) {
				knownThread.setLastNoteId(newest);
				threadUpdates.add(knownThread);
			}
		}
	}

	private static boolean hasMoved(Watched resource, GitLabWatchedResource known, boolean firstSight) {
		if (firstSight || known.getLastUpdatedAt() == null || resource.updatedAt() == null) {
			return true;
		}
		return resource.updatedAt().isAfter(known.getLastUpdatedAt());
	}

	/*
	 * a branch moving is news to whoever is expected to look at it, so a resource watched only because
	 * you once replied to it stays quiet: the replies to you are what you were kept for. your own
	 * pushes are never news, for the same reason your own replies are not.
	 */
	private static boolean announcesPush(Watched resource, GitLabWatchedResource known, Long selfId) {
		return resource.reason().announcesPushes()
				&& resource.type() == GitLabResourceType.MERGE_REQUEST
				&& resource.sha() != null
				&& known.getLastSha() != null
				&& !known.getLastSha().equals(resource.sha())
				&& !Objects.equals(resource.authorId(), selfId);
	}

	/*
	 * the name is the merge request's author, which is whose branch moved. GitLab's list response does
	 * not say who pushed, and asking would cost a request per push for a distinction that only differs
	 * when someone commits to a branch that is not theirs.
	 */
	private static IncomingItem changesPushed(Watched resource, Instant now) {
		Priority priority = resource.reason() == GitLabWatchReason.REVIEWING ? Priority.HIGH : Priority.NORMAL;
		return new IncomingItem(
				"mr-commits:" + resource.projectId() + ":" + resource.iid(),
				"changes_pushed",
				priority,
				resource.title(),
				"New commits since Sift last looked.",
				resource.authorName(),
				resource.authorAvatarUrl(),
				resource.projectPath(),
				null,
				resource.webUrl(),
				now,
				resource.updatedAt() == null ? now : resource.updatedAt(),
				null,
				// commits landing is an event; it does not un-happen on the next sweep
				false);
	}

	private static IncomingItem merged(GitLabWatchedResource known, GitLabResponses.MergeRequest mergeRequest,
			Instant now) {

		GitLabResponses.User by = mergeRequest.whoMerged();
		Instant at = mergeRequest.mergedAt();
		if (at == null) {
			at = mergeRequest.updatedAt() == null ? now : mergeRequest.updatedAt();
		}

		return new IncomingItem(
				"mr-merged:" + known.getProjectId() + ":" + known.getResourceIid(),
				"mr_merged",
				// nothing is waiting on you any more; this is the row that says so
				Priority.NORMAL,
				mergeRequest.title() == null ? known.getTitle() : mergeRequest.title(),
				null,
				by == null ? null : by.name(),
				by == null ? null : by.avatarUrl(),
				GitLabUrls.projectPath(mergeRequest.references()),
				GitLabUrls.projectUrl(mergeRequest.webUrl()),
				mergeRequest.webUrl() == null ? known.getWebUrl() : mergeRequest.webUrl(),
				mergeRequest.createdAt() == null ? at : mergeRequest.createdAt(),
				at,
				null,
				// it was merged once. the next sweep not mentioning it says nothing.
				false);
	}

	private static IncomingItem thread(Watched resource, GitLabResponses.Discussion discussion,
			List<GitLabResponses.Note> unseen, String kind) {

		GitLabResponses.Note newest = unseen.get(unseen.size() - 1);
		return new IncomingItem(
				"thread:" + discussion.id(),
				kind,
				Priority.NORMAL,
				resource.title(),
				snippet(newest.body()),
				newest.author() == null ? null : newest.author().name(),
				newest.author() == null ? null : newest.author().avatarUrl(),
				resource.projectPath(),
				null,
				// deep link to the note, so clicking lands on what changed rather than the top
				resource.webUrl() + "#note_" + newest.id(),
				newest.createdAt(),
				newest.createdAt(),
				null,
				// a reply arrived once. absence next sweep says nothing about whether it was read.
				false);
	}

	private static String snippet(String body) {
		if (body == null) {
			return null;
		}
		String flat = body.replaceAll("\\s+", " ").trim();
		if (flat.length() <= SNIPPET_LENGTH) {
			return flat;
		}
		return flat.substring(0, SNIPPET_LENGTH).trim() + "...";
	}

	/** The same resource arrives from several lists; the strongest reason is the one worth keeping. */
	private static List<Watched> deduplicate(List<Watched> watched) {
		Map<String, Watched> unique = new LinkedHashMap<>();
		for (Watched resource : watched) {
			Watched existing = unique.get(resource.key());
			if (existing == null || resource.reason().compareTo(existing.reason()) < 0) {
				unique.put(resource.key(), resource);
			}
		}
		return List.copyOf(unique.values());
	}
}
