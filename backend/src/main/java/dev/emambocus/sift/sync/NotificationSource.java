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
	 * False while the source still has older history to read. A source that reads everything it has on
	 * every sweep is always complete; one that walks a large history backwards is not, and a list that
	 * is still filling in should be able to say so rather than looking short for no reason.
	 */
	default boolean historyComplete(SourceCredential credential) {
		return true;
	}
}
