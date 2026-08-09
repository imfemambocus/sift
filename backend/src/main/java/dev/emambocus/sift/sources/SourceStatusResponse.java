package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
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
		/** True while a read of this source is running, which is what the page shows as "syncing now". */
		boolean syncing) {

	public static SourceStatusResponse of(SourceCredential credential, long itemCount, boolean historyComplete,
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
				historyComplete,
				syncing);
	}
}
