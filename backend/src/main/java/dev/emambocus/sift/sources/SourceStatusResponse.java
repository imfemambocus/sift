package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.sync.SourceHistory;
import java.time.Instant;

public record SourceStatusResponse(
		String source,
		String instanceUrl,
		String credentialType,
		String status,
		String lastError,
		Instant lastSyncAt,
		long itemCount,
		/** Which account at the source, once a sweep has learned it. Null before that. */
		String account,
		/** False while the source is still reading older history, so the page can say so. */
		boolean historyComplete,
		/** How far back that reading has reached, for a source that walks a history. Null otherwise. */
		Instant historyFrom,
		/** True when successive reads stopped reaching anything older, so a page can warn about it. */
		boolean historyStalled,
		/** True when this source can be told to read its history again from the beginning. */
		boolean canReread,
		/** True while a read of this source is running, which is what the page shows as "syncing now". */
		boolean syncing) {

	public static SourceStatusResponse of(SourceCredential credential, long itemCount, SourceHistory history,
			boolean syncing) {
		return new SourceStatusResponse(
				credential.getSource().slug(),
				credential.getInstanceUrl(),
				credential.getCredentialType().name(),
				credential.getLastSyncStatus().name(),
				credential.getLastError(),
				credential.getLastSyncAt(),
				itemCount,
				credential.getAccountLabel(),
				history.complete(),
				history.readBackTo(),
				history.stalled(),
				history.rereadable(),
				syncing);
	}
}
