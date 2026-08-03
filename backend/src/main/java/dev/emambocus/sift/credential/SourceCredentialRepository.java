package dev.emambocus.sift.credential;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

	/*
	 * a targeted update rather than loading the entity and saving it back. saving it back writes
	 * every column, including the token, and when the token is exactly what could not be decrypted
	 * that write is a null into a NOT NULL column: the transaction rolls back, the outcome is never
	 * recorded, and the sweep retries the same broken credential forever without ever telling anyone.
	 * this also stops a successful sweep pointlessly re-encrypting the token every time.
	 */
	@Modifying
	@Query("""
			update SourceCredential credential
			   set credential.lastSyncAt = :at,
			       credential.lastSyncStatus = :status,
			       credential.lastError = :error
			 where credential.id = :id
			""")
	int recordSyncOutcome(@Param("id") UUID id, @Param("at") Instant at,
			@Param("status") SyncStatus status, @Param("error") String error);

	void delete(SourceCredential credential);
}
