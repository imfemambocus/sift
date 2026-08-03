package dev.emambocus.sift.credential;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} rather than {@code JpaRepository} on purpose: this way the
 * whole query surface is written out here, so nothing user-scoped can be reached by an inherited
 * method nobody chose to expose.
 */
public interface SourceCredentialRepository extends Repository<SourceCredential, UUID> {

	<S extends SourceCredential> S save(S credential);

	Optional<SourceCredential> findByUserIdAndSource(UUID userId, SourceType source);

	List<SourceCredential> findByUserId(UUID userId);

	/**
	 * For the scheduled sweep, which legitimately has no user context. Anything serving a request
	 * must scope by user instead.
	 */
	Optional<SourceCredential> findById(UUID id);

	/** The sweep skips {@code AUTH_FAILED}: a rejected token will not start working on a retry. */
	List<SourceCredential> findByLastSyncStatusNot(SyncStatus status);

	void delete(SourceCredential credential);
}
