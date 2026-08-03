package dev.emambocus.sift.feed;

public enum Priority {

	/** Someone is waiting on you specifically: assigned, review requested, approval required. */
	HIGH,

	/** Worth knowing today, but nobody is blocked on you. */
	NORMAL,

	/** Background noise you asked to keep. */
	LOW
}
