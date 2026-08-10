package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;

/**
 * A place things needing your attention come from. Adding one is a new implementation of this and a
 * new {@link SourceType} constant: the scheduler and the feed endpoint work on {@link IncomingItem}
 * and never on source-shaped data.
 *
 * <p>Nothing here proves a credential before it is stored. An OAuth grant cannot be a typo, and the
 * exchange that issued it has already proved it works, so the seam is these two methods.
 */
public interface NotificationSource {

	SourceType id();

	/**
	 * Reads the source. Anything the source has to remember about how far it got belongs in the
	 * returned {@link SourceFetch}, which the sweep commits only once the rows are stored.
	 */
	SourceFetch fetch(SourceCredential credential);

	/**
	 * How much of the source's history is here. A source that reads everything it has on every sweep is
	 * always complete; one that walks a large history backwards is not, and a list that is still filling
	 * in should be able to say so rather than looking short for no reason.
	 */
	default SourceHistory history(SourceCredential credential) {
		return SourceHistory.COMPLETE;
	}

	/**
	 * Forgets how much of the source has been read, so the next read starts again at the newest end and
	 * walks back through all of it. False from a source that has nothing to forget.
	 *
	 * <p>Only ever for a cursor, never for a watermark. A cursor says how much of a corpus has been
	 * read, and the rows are the only copy of what it read, so reading it again fills them back in. A
	 * watermark says what has already been announced, and forgetting one announces months of settled
	 * news a second time.
	 */
	default boolean rereadHistory(SourceCredential credential) {
		return false;
	}
}
