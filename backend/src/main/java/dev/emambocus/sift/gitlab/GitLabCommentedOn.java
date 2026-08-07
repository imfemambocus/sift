package dev.emambocus.sift.gitlab;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage two of participation: the merge requests and issues someone is part of only because they
 * left a comment, with no assignee, reviewer or author relationship to find them by.
 *
 * <p>GitLab's activity feed is the only place that says so, so the token owner's own
 * {@code commented} events are read and the resources behind them are looked up in one request per
 * project rather than one per resource.
 */
@Component
class GitLabCommentedOn {

	private static final Logger log = LoggerFactory.getLogger(GitLabCommentedOn.class);

	/*
	 * how long a comment keeps you part of something. long enough that a discussion which goes quiet
	 * over a weekend still reaches you, short enough that a resource you have finished with stops
	 * being looked up on every sweep.
	 */
	private static final Duration WINDOW = Duration.ofDays(14);

	/** A URL cannot grow forever, and GitLab takes {@code iids[]} repeated rather than joined. */
	private static final int IIDS_PER_REQUEST = 50;

	/** What a prolific commenter is allowed to add to one sweep. */
	private static final int MAX_RESOURCES = 100;

	/**
	 * The raw responses, not {@code Watched}: turning a GitLab record into something worth watching is
	 * {@code GitLabSource}'s job, and it already does it for five other lists.
	 */
	record Found(List<GitLabResponses.MergeRequest> mergeRequests, List<GitLabResponses.Issue> issues) {

		static final Found NOTHING = new Found(List.of(), List.of());
	}

	/** One request's worth: everything of one type wanted out of one project. */
	private record Batch(GitLabResourceType type, long projectId) {
	}

	private final GitLabClient client;
	private final Clock clock;

	GitLabCommentedOn(GitLabClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	/**
	 * @param alreadyWatched resource keys the lists have already produced, which cost nothing to watch
	 *                       and must not be looked up again
	 */
	Found discover(GitLabAccess access, Set<String> alreadyWatched, int maxPages) {
		List<GitLabResponses.Event> events;
		try {
			LocalDate after = LocalDate.ofInstant(clock.instant().minus(WINDOW), ZoneOffset.UTC);
			events = client.fetchCommentedEvents(access, after, maxPages);
		}
		catch (RuntimeException ex) {
			/*
			 * this runs after the to-dos and every list have already been read, so an instance that
			 * will not answer the activity feed must cost the comment-only resources rather than the
			 * whole sweep. the credential itself has already proved itself on /user by now.
			 */
			log.warn("could not read your own comments, so nothing commented-only was discovered: {}",
					ex.getMessage());
			return Found.NOTHING;
		}

		Map<Batch, Set<Long>> wanted = wanted(events, alreadyWatched);
		if (wanted.isEmpty()) {
			return Found.NOTHING;
		}

		List<GitLabResponses.MergeRequest> mergeRequests = new ArrayList<>();
		List<GitLabResponses.Issue> issues = new ArrayList<>();
		for (Map.Entry<Batch, Set<Long>> entry : wanted.entrySet()) {
			read(access, entry.getKey(), entry.getValue(), maxPages, mergeRequests, issues);
		}
		return new Found(mergeRequests, issues);
	}

	private static Map<Batch, Set<Long>> wanted(List<GitLabResponses.Event> events, Set<String> alreadyWatched) {
		Map<Batch, Set<Long>> wanted = new LinkedHashMap<>();
		int found = 0;

		// newest first, which is the order GitLab answers in and the order to keep if the cap bites
		for (GitLabResponses.Event event : events) {
			GitLabResponses.Note note = event.note();
			if (event.projectId() == null || note == null || note.noteableIid() == null) {
				continue;
			}
			GitLabResourceType type = GitLabResourceType.ofNoteable(note.noteableType()).orElse(null);
			if (type == null
					|| alreadyWatched.contains(GitLabParticipation.Watched.key(type, event.projectId(), note.noteableIid()))) {
				continue;
			}
			if (found == MAX_RESOURCES) {
				// never truncate quietly: a capped pass otherwise looks exactly like a complete one
				log.warn("stopped at {} commented-on resources; anything older than that was not watched",
						MAX_RESOURCES);
				break;
			}
			Set<Long> iids = wanted.computeIfAbsent(new Batch(type, event.projectId()), key -> new LinkedHashSet<>());
			if (iids.add(note.noteableIid())) {
				found++;
			}
		}
		return wanted;
	}

	private void read(GitLabAccess access, Batch batch, Set<Long> iids, int maxPages,
			List<GitLabResponses.MergeRequest> mergeRequests, List<GitLabResponses.Issue> issues) {

		for (List<Long> chunk : chunks(iids)) {
			try {
				if (batch.type() == GitLabResourceType.MERGE_REQUEST) {
					mergeRequests.addAll(
							client.fetchMergeRequestsByIid(access, batch.projectId(), chunk, maxPages));
				}
				else {
					issues.addAll(client.fetchIssuesByIid(access, batch.projectId(), chunk, maxPages));
				}
			}
			catch (RuntimeException ex) {
				/*
				 * a project you commented in and have since lost sight of answers 404, and one
				 * inaccessible project must not lose the rest of the sweep. only open resources are
				 * asked for, so anything already merged or closed simply comes back absent.
				 */
				log.warn("could not read the {} you commented on in project {}: {}",
						batch.type().pathSegment(), batch.projectId(), ex.getMessage());
			}
		}
	}

	private static List<List<Long>> chunks(Set<Long> iids) {
		List<Long> all = List.copyOf(iids);
		List<List<Long>> chunks = new ArrayList<>();
		for (int start = 0; start < all.size(); start += IIDS_PER_REQUEST) {
			chunks.add(all.subList(start, Math.min(start + IIDS_PER_REQUEST, all.size())));
		}
		return chunks;
	}
}
