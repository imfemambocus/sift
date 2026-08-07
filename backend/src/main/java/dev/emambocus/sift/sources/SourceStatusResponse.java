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
		long itemCount) {

	public static SourceStatusResponse of(SourceCredential credential, long itemCount) {
		return new SourceStatusResponse(
				credential.getSource().slug(),
				credential.getInstanceUrl(),
				credential.getCredentialType().name(),
				credential.getLastSyncStatus().name(),
				credential.getLastError(),
				credential.getLastSyncAt(),
				itemCount);
	}
}
