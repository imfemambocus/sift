package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import java.util.List;

/**
 * A source that can be told what you have read, so the decision does not stay inside Sift.
 *
 * <p>Separate from {@link NotificationSource} because most sources cannot do this, and the ones that
 * can only do it with a wider grant than reading needs. A source that does not implement this is not
 * missing anything: read state is Sift's own until somebody asks for it to travel.
 */
public interface SourceReadWriter {

	SourceType id();

	/**
	 * Applies one read decision upstream.
	 *
	 * @param sourceIds the {@code source_id} of each row, in Sift's own form, which the implementation
	 *     translates back into whatever the source calls a message
	 */
	void applyRead(SourceCredential credential, List<String> sourceIds, boolean read);
}
