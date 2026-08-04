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

	/** You only left a comment on it: replies to you are news, a branch moving is not. */
	COMMENTED;

	boolean announcesPushes() {
		return this != COMMENTED;
	}
}
