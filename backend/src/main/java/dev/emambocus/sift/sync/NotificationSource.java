package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import java.util.List;

/**
 * A place things needing your attention come from. Adding one is a new implementation of this and a
 * new {@link SourceType} constant: the scheduler, the feed endpoint and the rules all work on
 * {@link IncomingItem} and never on source-shaped data.
 */
public interface NotificationSource {

	SourceType id();

	/** Proves a credential works before it is stored, and says whose account it is. */
	SourceAccount verify(String instanceUrl, String token);

	List<IncomingItem> fetch(SourceCredential credential);
}
