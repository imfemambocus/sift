package dev.emambocus.sift.credential;

public enum SyncStatus {

	NEVER_RUN,

	OK,

	/** The token was rejected, so the user has to reconnect; retrying on a schedule will not help. */
	AUTH_FAILED,

	/** Anything else, which is worth retrying on the next sweep. */
	ERROR
}
