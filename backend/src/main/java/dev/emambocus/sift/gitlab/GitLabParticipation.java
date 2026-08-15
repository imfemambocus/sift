package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;
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

	private static final String APPROVED = "approved this merge request";

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

	/** What one sweep found, and the watch state it must write down once those rows are stored. */
	record Collected(
			List<IncomingItem> items,
			List<GitLabWatchedResource> resources,
			List<GitLabWatchedDiscussion> discussions,
			List<GitLabWatchedResource> finished) {
	}

	private final GitLabClient client;
	private final GitLabWatchStore store;
	private final Clock clock;

	GitLabParticipation(GitLabClient client, GitLabWatchStore store, Clock clock) {
		this.client = client;
		this.store = store;
		this.clock = clock;
	}

	/**
	 * Writes down what a sweep learned. Held back until its rows are stored: a watermark written first
	 * turns a failed sweep into replies nobody is ever told about.
	 */
	void commit(Collected collected) {
		store.save(collected.resources(), collected.discussions());
		store.forget(collected.finished());
	}

	Collected collect(SourceCredential credential, GitLabAccess access, Long selfId,
			List<Watched> watched, int maxPages) {
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
			else if (announcesPush(resource, known)) {
				items.add(changesPushed(resource, now));
			}

			boolean moved = hasMoved(resource, known, firstSight);
			if (moved) {
				readThreads(userId, access, selfId, resource, firstSight, knownThreads, items, threadUpdates, now,
						maxPages);
			}

			if (watchesPipeline(resource, known, moved)) {
				readPipeline(access, resource, known, firstSight, items, now);
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
			settle(access, known, items, finished, now);
		}

		return new Collected(items, resourceUpdates, threadUpdates, finished);
	}

	/*
	 * a merge request that has left the opened lists has either been merged, been closed, or stopped
	 * involving you. only the first is news, and the answer needs one request, which is why it is
	 * asked here rather than guessed from the absence.
	 */
	private void settle(GitLabAccess access, GitLabWatchedResource known, List<IncomingItem> items,
			List<GitLabWatchedResource> finished, Instant now) {

		if (known.getResourceType() != GitLabResourceType.MERGE_REQUEST) {
			finished.add(known);
			return;
		}

		Optional<GitLabResponses.MergeRequest> found;
		try {
			found = client.fetchMergeRequest(access, known.getProjectId(), known.getResourceIid());
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

	private void readThreads(UUID userId, GitLabAccess access, Long selfId, Watched resource, boolean firstSight,
			Map<String, GitLabWatchedDiscussion> knownThreads, List<IncomingItem> items,
			List<GitLabWatchedDiscussion> threadUpdates, Instant now, int maxPages) {

		List<GitLabResponses.Discussion> discussions;
		try {
			discussions = client.fetchDiscussions(access, resource.projectId(), resource.type(), resource.iid(),
					maxPages);
		}
		catch (RuntimeException ex) {
			/*
			 * one unreadable resource must not lose the whole sweep, including the to-dos already
			 * fetched. it is reported and skipped, and its stored timestamp is not advanced: the next
			 * sweep tries again.
			 */
			log.warn("could not read discussions on {} {} in project {}: {}",
					resource.type(), resource.iid(), resource.projectId(), ex.getMessage());
			return;
		}

		for (GitLabResponses.Discussion discussion : discussions) {
			if (discussion.id() == null || discussion.notes() == null || discussion.notes().isEmpty()) {
				continue;
			}

			// a note with no id cannot be compared against a watermark. it is not usable at all
			List<GitLabResponses.Note> notes = discussion.notes().stream()
					.filter(note -> note.id() != null)
					.toList();
			if (notes.isEmpty()) {
				continue;
			}

			GitLabWatchedDiscussion knownThread = knownThreads.get(discussion.id());
			long alreadySeen = knownThread == null ? 0 : knownThread.getLastNoteId();
			long newest = notes.stream().mapToLong(GitLabResponses.Note::id).max().orElse(0);

			// what you did yourself is never news to you, in a reply and in a system note alike
			List<GitLabResponses.Note> fresh = notes.stream()
					.filter(note -> note.id() > alreadySeen)
					.filter(note -> note.author() == null || !Objects.equals(note.author().id(), selfId))
					.toList();

			// a system note is GitLab narrating itself, not a person writing to you
			List<GitLabResponses.Note> unseen = fresh.stream()
					.filter(note -> !Boolean.TRUE.equals(note.system()))
					.toList();

			/*
			 * a resource seen for the first time is only baselined. without this, connecting an
			 * account would emit a row for every thread the user has ever been in.
			 */
			if (!firstSight) {
				if (!unseen.isEmpty()) {
					String kind = knownThread == null ? "new_thread" : "new_comment";
					items.add(thread(resource, discussion, unseen, kind));
				}
				announceApprovals(resource, fresh, items);
			}

			if (knownThread == null) {
				threadUpdates.add(GitLabWatchedDiscussion.of(userId, discussion.id(), newest, now));
			}
			else if (newest > alreadySeen) {
				knownThread.setLastNoteId(newest);
				threadUpdates.add(knownThread);
			}
		}
	}

	/*
	 * the merge request's own pipeline, which no list carries: only the single merge request endpoint
	 * has head_pipeline. this costs one request, and is asked for as rarely as it can be.
	 *
	 * two triggers, because neither covers the other. a merge request that moved may have started a
	 * pipeline, and a pipeline already seen running has to be looked at again until it reaches a
	 * verdict, since GitLab does not promise to move the merge request when its pipeline finishes.
	 */
	private static boolean watchesPipeline(Watched resource, GitLabWatchedResource known, boolean moved) {
		return resource.reason().announcesBranchEvents()
				&& resource.type() == GitLabResourceType.MERGE_REQUEST
				&& (moved || known.isPipelinePending());
	}

	private void readPipeline(GitLabAccess access, Watched resource, GitLabWatchedResource known,
			boolean firstSight, List<IncomingItem> items, Instant now) {

		Optional<GitLabResponses.MergeRequest> found;
		try {
			found = client.fetchMergeRequest(access, resource.projectId(), resource.iid());
		}
		catch (RuntimeException ex) {
			// as with an unreadable thread: report it, leave the stored verdict alone, try again later
			log.warn("could not read the pipeline on merge request {} in project {}: {}",
					resource.iid(), resource.projectId(), ex.getMessage());
			return;
		}

		GitLabResponses.Pipeline pipeline = found.map(GitLabResponses.MergeRequest::headPipeline).orElse(null);
		if (pipeline == null || pipeline.id() == null) {
			// nothing configured, or nothing has run yet. there is no verdict to keep or to contradict.
			known.setPipelinePending(false);
			return;
		}

		known.setPipelinePending(!pipeline.settled());
		if (!pipeline.settled()) {
			return;
		}

		/*
		 * a first sight is baselined, exactly as a thread is: without it, connecting an account emits a
		 * row for every merge request whose pipeline happens to be red today. anything after that is a
		 * verdict Sift watched arrive, including the first one, which is why the test is the sighting
		 * and not whether a verdict is already stored.
		 */
		if (!firstSight) {
			pipelineItem(resource, known, pipeline, now).ifPresent(items::add);
		}

		known.setPipelineId(pipeline.id());
		known.setPipelineStatus(pipeline.status());
	}

	/*
	 * a failure is news once per pipeline. a red one still red on the next sweep says nothing, and a
	 * replacement that fails again does. a fix is news only after a failure, which is what makes the
	 * stored verdict worth keeping rather than the status of whatever ran last.
	 */
	private static Optional<IncomingItem> pipelineItem(Watched resource, GitLabWatchedResource known,
			GitLabResponses.Pipeline pipeline, Instant now) {

		boolean wasFailing = "failed".equals(known.getPipelineStatus());
		boolean samePipeline = pipeline.id().equals(known.getPipelineId());

		if (pipeline.failed() && !(wasFailing && samePipeline)) {
			return Optional.of(pipelineRow(resource, pipeline, "pipeline_failed",
					"The pipeline on this merge request failed.", now));
		}
		if (pipeline.succeeded() && wasFailing) {
			return Optional.of(pipelineRow(resource, pipeline, "pipeline_fixed",
					"The pipeline on this merge request passed again.", now));
		}
		return Optional.empty();
	}

	/*
	 * the merge request's url rather than the pipeline's: this shares the group of everything else
	 * about that merge request. the pipeline of a merge request is read from its page anyway, and a
	 * url of its own would give one merge request two places in the list.
	 */
	private static IncomingItem pipelineRow(Watched resource, GitLabResponses.Pipeline pipeline, String kind,
			String body, Instant now) {

		GitLabResponses.User by = pipeline.user();
		return new IncomingItem(
				"mr-" + kind.replace('_', '-') + ":" + resource.projectId() + ":" + resource.iid()
						+ ":" + pipeline.id(),
				kind,
				resource.title(),
				body,
				by == null ? resource.authorName() : by.name(),
				by == null ? resource.authorAvatarUrl() : by.avatarUrl(),
				resource.projectPath(),
				null,
				resource.webUrl(),
				null,
				pipeline.updatedAt() == null ? now : pipeline.updatedAt(),
				pipeline.updatedAt() == null ? now : pipeline.updatedAt(),
				false,
				null,
				// a pipeline reached its verdict once; the next sweep not saying so again says nothing
				false);
	}

	private static boolean hasMoved(Watched resource, GitLabWatchedResource known, boolean firstSight) {
		if (firstSight || known.getLastUpdatedAt() == null || resource.updatedAt() == null) {
			return true;
		}
		return resource.updatedAt().isAfter(known.getLastUpdatedAt());
	}

	/*
	 * a resource watched only because you once replied to it stays quiet: the replies to you are what
	 * it is kept for. every other push is announced whoever made it, because a merge request's state
	 * row returns to unread on any change GitLab records, and that row cannot say what the change was.
	 */
	private static boolean announcesPush(Watched resource, GitLabWatchedResource known) {
		return resource.reason().announcesBranchEvents()
				&& resource.type() == GitLabResourceType.MERGE_REQUEST
				&& resource.sha() != null
				&& known.getLastSha() != null
				&& !known.getLastSha().equals(resource.sha());
	}

	/*
	 * the name is the merge request's author, which is whose branch moved. GitLab's list response does
	 * not say who pushed, and asking would cost a request per push for a distinction that only differs
	 * when someone commits to a branch that is not theirs.
	 */
	private static IncomingItem changesPushed(Watched resource, Instant now) {
		return new IncomingItem(
				"mr-commits:" + resource.projectId() + ":" + resource.iid(),
				"changes_pushed",
				resource.title(),
				"New commits since Sift last looked.",
				resource.authorName(),
				resource.authorAvatarUrl(),
				resource.projectPath(),
				null,
				resource.webUrl(),
				null,
				now,
				resource.updatedAt() == null ? now : resource.updatedAt(),
				false,
				null,
				// commits landing is an event; it does not un-happen on the next sweep
				false);
	}

	/*
	 * GitLab raises no to-do when somebody approves, and records it as a system note on the merge
	 * request. Its body is English whatever the user's locale, and the body of an unapproval contains
	 * the body of an approval. the test is therefore an exact match, never a prefix or a substring.
	 */
	private static void announceApprovals(Watched resource, List<GitLabResponses.Note> fresh,
			List<IncomingItem> items) {

		if (resource.type() != GitLabResourceType.MERGE_REQUEST) {
			return;
		}
		for (GitLabResponses.Note note : fresh) {
			if (Boolean.TRUE.equals(note.system()) && note.body() != null && APPROVED.equals(note.body().trim())) {
				items.add(approved(resource, note));
			}
		}
	}

	private static IncomingItem approved(Watched resource, GitLabResponses.Note note) {
		return new IncomingItem(
				"mr-approved:" + resource.projectId() + ":" + resource.iid() + ":" + note.id(),
				"mr_approved",
				resource.title(),
				null,
				note.author() == null ? null : note.author().name(),
				note.author() == null ? null : note.author().avatarUrl(),
				resource.projectPath(),
				null,
				// the note anchor: the group key still comes out as the merge request itself
				resource.webUrl() + "#note_" + note.id(),
				null,
				note.createdAt(),
				note.createdAt(),
				false,
				null,
				// it was approved once. the next sweep not saying so again says nothing.
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
				mergeRequest.title() == null ? known.getTitle() : mergeRequest.title(),
				null,
				by == null ? null : by.name(),
				by == null ? null : by.avatarUrl(),
				GitLabUrls.projectPath(mergeRequest.references()),
				GitLabUrls.projectUrl(mergeRequest.webUrl()),
				mergeRequest.webUrl() == null ? known.getWebUrl() : mergeRequest.webUrl(),
				null,
				mergeRequest.createdAt() == null ? at : mergeRequest.createdAt(),
				at,
				false,
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
				resource.title(),
				snippet(newest.body()),
				newest.author() == null ? null : newest.author().name(),
				newest.author() == null ? null : newest.author().avatarUrl(),
				resource.projectPath(),
				null,
				// deep link to the note: clicking lands on what changed rather than the top
				resource.webUrl() + "#note_" + newest.id(),
				null,
				newest.createdAt(),
				newest.createdAt(),
				false,
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
