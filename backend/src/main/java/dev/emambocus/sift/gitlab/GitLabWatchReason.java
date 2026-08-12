package dev.emambocus.sift.gitlab;

/**
 * Why a resource is watched at all, which decides what it is allowed to announce.
 *
 * <p>Declared strongest first: the same merge request arrives from several lists, and
 * {@code GitLabParticipation.deduplicate} keeps whichever reason compares lowest.
 */
enum GitLabWatchReason {

	/** Someone is waiting on your review, so commits landing on it are the whole point. */
	REVIEWING,

	/** Assigned to you, or yours. */
	INVOLVED,

	/** You only left a comment on it: replies to you are news, the branch and its pipeline are not. */
	COMMENTED;

	/** Whether commits landing and the pipeline's verdict are worth a row, or only replies to you. */
	boolean announcesBranchEvents() {
		return this != COMMENTED;
	}
}
