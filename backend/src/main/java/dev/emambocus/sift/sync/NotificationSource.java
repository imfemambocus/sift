package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import java.util.List;

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

	List<IncomingItem> fetch(SourceCredential credential);
}
