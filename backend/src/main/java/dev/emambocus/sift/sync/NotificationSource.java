package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import java.util.List;

/**
 * A place things needing your attention come from. Adding one is a new implementation of this and a
 * new {@link SourceType} constant: the scheduler and the feed endpoint work on {@link IncomingItem}
 * and never on source-shaped data.
 *
 * <p>There is no {@code verify} here any more. A credential used to be proved before it was stored,
 * because it was pasted in and could be a typo. An OAuth grant cannot be, and the exchange that
 * issued it has already proved it, so the seam is one method.
 */
public interface NotificationSource {

	SourceType id();

	List<IncomingItem> fetch(SourceCredential credential);
}
