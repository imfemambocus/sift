package dev.emambocus.sift.credential;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * every lookup is scoped by user on purpose. there is deliberately no plain findById here, so a
 * caller cannot reach another tenant's credential by id alone
 */
public interface SourceCredentialRepository extends JpaRepository<SourceCredential, UUID> {

	Optional<SourceCredential> findByUserIdAndSource(UUID userId, SourceType source);

	List<SourceCredential> findByUserId(UUID userId);

	Optional<SourceCredential> findByIdAndUserId(UUID id, UUID userId);
}
