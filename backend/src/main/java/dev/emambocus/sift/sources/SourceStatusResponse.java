package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.sync.SourceAccount;
import java.time.Instant;

/**
 * @param account only present on the response to a connect, since Sift does not store the remote
 * account and will not spend an API call to look it up just to list what is connected.
 */
public record SourceStatusResponse(
		String source,
		String instanceUrl,
		String credentialType,
		String status,
		String lastError,
		Instant lastSyncAt,
		long itemCount,
		SourceAccount account) {

	public static SourceStatusResponse of(SourceCredential credential, long itemCount, SourceAccount account) {
		return new SourceStatusResponse(
				credential.getSource().slug(),
				credential.getInstanceUrl(),
				credential.getCredentialType().name(),
				credential.getLastSyncStatus().name(),
				credential.getLastError(),
				credential.getLastSyncAt(),
				itemCount,
				account);
	}
}
