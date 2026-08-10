package dev.emambocus.sift.gmail;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Top level, not nested: Spring Data does not scan a repository interface inside another class. */
interface GmailSyncStateRepository extends Repository<GmailSyncState, UUID> {

	Optional<GmailSyncState> findByCredentialId(UUID credentialId);

	GmailSyncState save(GmailSyncState state);

	void deleteByCredentialId(UUID credentialId);
}
