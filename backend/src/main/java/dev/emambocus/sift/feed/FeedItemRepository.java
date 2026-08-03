package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} rather than {@code JpaRepository} on purpose: every method is
 * written out here and every one of them is scoped by user, so there is no inherited way to reach
 * another tenant's items.
 */
public interface FeedItemRepository extends Repository<FeedItem, UUID> {

	<S extends FeedItem> List<S> saveAll(Iterable<S> items);

	List<FeedItem> findByUserIdAndSource(UUID userId, SourceType source);

	List<FeedItem> findByUserIdAndResolvedAtIsNullOrderBySourceCreatedAtDesc(UUID userId);

	List<FeedItem> findByUserIdAndSourceAndResolvedAtIsNullOrderBySourceCreatedAtDesc(
			UUID userId, SourceType source);

	long countByUserIdAndSourceAndResolvedAtIsNull(UUID userId, SourceType source);

	void deleteByUserIdAndSource(UUID userId, SourceType source);
}
