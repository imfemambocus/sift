package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends the bare {@link Repository} rather than {@code JpaRepository} on purpose: every method is
 * written out here and every one of them is scoped by user, so there is no inherited way to reach
 * another tenant's items.
 */
public interface FeedItemRepository extends Repository<FeedItem, UUID> {

	<S extends FeedItem> List<S> saveAll(Iterable<S> items);

	List<FeedItem> findByUserIdAndSource(UUID userId, SourceType source);

	long countByUserIdAndSourceAndResolvedAtIsNull(UUID userId, SourceType source);

	/*
	 * every number the app shows without showing the rows: the All / Unread / Read control, Home's
	 * "11 waiting", and the number on the tab. one statement for every source, since every page wants
	 * all of them at once.
	 *
	 * resolved rows are in `total` deliberately. the feed is the whole history, and read against
	 * unread is the only axis it narrows on, so a completed to-do is still one of the rows in it.
	 */
	@Query("""
			select new dev.emambocus.sift.feed.FeedCounts(
			           item.source,
			           count(item),
			           count(case when item.readAt is null then 1 end),
			           count(case when item.resolvedAt is null then 1 end),
			           count(case when item.resolvedAt is null and item.readAt is null then 1 end))
			  from FeedItem item
			 where item.userId = :userId
			 group by item.source
			""")
	List<FeedCounts> countBySource(@Param("userId") UUID userId);

	@Query("""
			select new dev.emambocus.sift.feed.KindCount(item.source, item.kind, count(item))
			  from FeedItem item
			 where item.userId = :userId and item.resolvedAt is null
			 group by item.source, item.kind
			""")
	List<KindCount> countWaitingByKind(@Param("userId") UUID userId);

	/*
	 * a targeted update rather than loading the entity and saving it back, for the same reason the
	 * sync outcome is written this way: marking one row read must not rewrite every other column.
	 * the user id is in the where clause so another tenant's item cannot be reached by id alone, and
	 * the row count is what tells the caller whether it existed.
	 */
	@Modifying
	@Query("update FeedItem item set item.readAt = :readAt where item.id = :id and item.userId = :userId")
	int updateReadAt(@Param("id") UUID id, @Param("userId") UUID userId, @Param("readAt") Instant readAt);

	/*
	 * one statement rather than the client patching every id, which for a full feed would be hundreds
	 * of requests. only the unread are touched, so an item read yesterday keeps the timestamp it had.
	 *
	 * resolved rows are included: they are part of the feed, so leaving them behind would be a "mark
	 * all read" that visibly did not. nothing invisible is reached either way, because a row is
	 * stamped read at the moment it resolves.
	 *
	 * two methods rather than one with a nullable source: passing null for an enum parameter leaves
	 * hibernate guessing at the type, and the guess is not always the one the column wants.
	 */
	@Modifying
	@Query("""
			update FeedItem item set item.readAt = :readAt
			 where item.userId = :userId and item.readAt is null
			""")
	int markAllRead(@Param("userId") UUID userId, @Param("readAt") Instant readAt);

	@Modifying
	@Query("""
			update FeedItem item set item.readAt = :readAt
			 where item.userId = :userId and item.source = :source and item.readAt is null
			""")
	int markAllRead(@Param("userId") UUID userId, @Param("source") SourceType source,
			@Param("readAt") Instant readAt);

	void deleteByUserIdAndSource(UUID userId, SourceType source);
}
