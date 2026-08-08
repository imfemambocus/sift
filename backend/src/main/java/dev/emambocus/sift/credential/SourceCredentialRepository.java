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

	/*
	 * targeted for the same reason the outcome is: this runs inside a sweep, so saving the whole
	 * entity would write a token and an outcome that the sweep has not decided yet.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update SourceCredential c set c.accountLabel = :label where c.id = :id and c.accountLabel is distinct from :label")
	int recordAccount(@Param("id") UUID id, @Param("label") String label);

	/*
	 * a renewed OAuth pair, written the same targeted way and for a second reason of its own: this
	 * runs in the middle of a sweep, so saving the whole entity would also write a sync outcome that
	 * is not known yet. GitLab invalidates the old refresh token on every renewal, so if this write
	 * is lost the connection is dead.
	 */
	@Modifying
	@Query("""
			update SourceCredential credential
			   set credential.accessToken = :accessToken,
			       credential.refreshToken = :refreshToken,
			       credential.expiresAt = :expiresAt
			 where credential.id = :id
			""")
	int replaceTokens(@Param("id") UUID id, @Param("accessToken") String accessToken,
			@Param("refreshToken") String refreshToken, @Param("expiresAt") Instant expiresAt);

	void delete(SourceCredential credential);
}
