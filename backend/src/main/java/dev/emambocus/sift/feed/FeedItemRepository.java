package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

	List<FeedItem> findByUserIdAndSourceAndSourceIdIn(UUID userId, SourceType source,
			Collection<String> sourceIds);

	/*
	 * the only rows a sweep's silence can resolve, which with the incoming ids is everything it needs
	 * to hold. a partial index carries the same predicate, so a mailbox of thousands answers this
	 * without being read.
	 */
	@Query("""
			select item from FeedItem item
			 where item.userId = :userId and item.source = :source
			   and item.resolveWhenAbsent = true and item.resolvedAt is null
			""")
	List<FeedItem> findResolvable(@Param("userId") UUID userId, @Param("source") SourceType source);

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

	/** One row's identity at its source, for telling that source what was just decided here. */
	@Query("select new dev.emambocus.sift.feed.SourceRow(i.source, i.sourceId) "
			+ "from FeedItem i where i.id = :id and i.userId = :userId")
	Optional<SourceRow> findSourceRow(@Param("id") UUID id, @Param("userId") UUID userId);

	/**
	 * Every unread row, before it stops being unread. Read inside the same transaction as the update
	 * that follows it, so what is reported upstream is exactly what changed here.
	 */
	@Query("select new dev.emambocus.sift.feed.SourceRow(i.source, i.sourceId) "
			+ "from FeedItem i where i.userId = :userId and i.readAt is null "
			+ "and (:source is null or i.source = :source)")
	List<SourceRow> findUnreadSourceRows(@Param("userId") UUID userId, @Param("source") SourceType source);

	/*
	 * read state the source itself reports, which is the direction a push from here cannot cover. the
	 * ids are a source's own, so the source is in the where clause as well as the user: two sources
	 * are free to use the same id for different things.
	 */
	@Modifying
	@Query("""
			update FeedItem item set item.readAt = :readAt
			 where item.userId = :userId and item.source = :source
			   and item.sourceId in :sourceIds and item.readAt is null
			""")
	int markReadBySourceId(@Param("userId") UUID userId, @Param("source") SourceType source,
			@Param("sourceIds") Collection<String> sourceIds, @Param("readAt") Instant readAt);

	@Modifying
	@Query("""
			update FeedItem item set item.readAt = null
			 where item.userId = :userId and item.source = :source
			   and item.sourceId in :sourceIds and item.readAt is not null
			""")
	int markUnreadBySourceId(@Param("userId") UUID userId, @Param("source") SourceType source,
			@Param("sourceIds") Collection<String> sourceIds);

	/*
	 * for a source that can name every unread row it holds: everything else of it has been read. the
	 * caller must pass a non-empty set, since `not in ()` is not valid.
	 */
	@Modifying
	@Query("""
			update FeedItem item set item.readAt = :readAt
			 where item.userId = :userId and item.source = :source
			   and item.sourceId not in :sourceIds and item.readAt is null
			""")
	int markReadExceptSourceId(@Param("userId") UUID userId, @Param("source") SourceType source,
			@Param("sourceIds") Collection<String> sourceIds, @Param("readAt") Instant readAt);

	/*
	 * for a thing the source no longer holds at all, where a resolved row would be wrong: resolving
	 * means finished, and the feed keeps it as history. this is a row with nothing left to be about.
	 */
	@Modifying
	@Query("""
			delete from FeedItem item
			 where item.userId = :userId and item.source = :source and item.sourceId in :sourceIds
			""")
	int deleteBySourceId(@Param("userId") UUID userId, @Param("source") SourceType source,
			@Param("sourceIds") Collection<String> sourceIds);

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
