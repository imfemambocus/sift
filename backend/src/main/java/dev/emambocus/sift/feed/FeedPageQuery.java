package dev.emambocus.sift.feed;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * The one statement that narrows, orders and pages the feed.
 *
 * <p>It is written out rather than derived, and it lives here rather than on
 * {@link FeedItemRepository}, because it takes far more parameters than a repository method should
 * and because the whole point of it is a shape Spring Data cannot derive: the page is a number of
 * <em>groups</em>, not a number of rows.
 */
@Component
public class FeedPageQuery {

	/*
	 * Read it as four steps.
	 *
	 * `matching` is every row that survives the filter and the search. The haystack a word is matched
	 * against, and the words of that haystack, are stored columns written when the row is: a feed
	 * holding a mailbox is large enough that rebuilding and re-splitting both per row per query was
	 * most of the cost of a search.
	 *
	 * The length band in front of levenshtein loses nothing, because an edit distance is never less
	 * than the difference in length. It is there to keep the call off the many words that cannot
	 * possibly be within the allowance.
	 *
	 * `ranked` gives each surviving group one sort key: min(sign * epoch(activity_at)) over its
	 * items. See FeedOrder.sign for why one expression covers both directions.
	 *
	 * `page` takes the groups themselves, which is what makes the limit a count of groups. The keyset
	 * comparison is a row constructor, so a tie on the timestamp falls through to the group key
	 * rather than letting two groups swap places between one request and the next.
	 *
	 * The final select then puts every item of those groups back, groups in page order and items in
	 * the same direction inside each one, so the client can group the list by walking it once.
	 *
	 * Every empty search list arrives as an empty string, which string_to_array turns into an empty
	 * array, which makes its `not exists` trivially true. So one statement serves a plain feed and a
	 * search, and there is no second query to keep in step with this one.
	 */
	private static final String SQL = """
			with args as (
			    select cast(:sign as int) as sign,
			           string_to_array(:words, ' ') as words,
			           string_to_array(:projects, ' ') as projects,
			           string_to_array(:actors, ' ') as actors,
			           string_to_array(:kinds, ' ') as kinds,
			           string_to_array(:urlParts, ' ') as url_parts
			),
			matching as (
			    select i.*
			      from feed_items i
			      cross join args a
			     where i.user_id = :userId
			       and (cast(:source as text) is null or i.source = cast(:source as text))
			       and (:filter = 'ALL'
			            or (:filter = 'UNREAD' and i.read_at is null)
			            or (:filter = 'READ' and i.read_at is not null))
			       and (cast(:searchRead as boolean) is null
			            or (cast(:searchRead as boolean) and i.read_at is not null)
			            or (not cast(:searchRead as boolean) and i.read_at is null))
			       and (cast(:after as timestamptz) is null
			            or i.activity_at >= cast(:after as timestamptz))
			       and (cast(:before as timestamptz) is null
			            or i.activity_at < cast(:before as timestamptz))
			       and (cast(:hasFiles as boolean) is null
			            or (i.attachments is not null) = cast(:hasFiles as boolean))
			       and not exists (
			           select 1 from unnest(a.projects) as want
			            where position(want in lower(coalesce(i.context_label, ''))) = 0)
			       and not exists (
			           select 1 from unnest(a.actors) as want
			            where position(want in lower(coalesce(i.actor_name, ''))) = 0)
			       and not exists (
			           select 1 from unnest(a.kinds) as want
			            where position(want in lower(i.kind)) = 0)
			       and not exists (
			           select 1 from unnest(a.url_parts) as want
			            where position(want in lower(i.url)) = 0)
			       and not exists (
			           select 1 from unnest(a.words) as want
			            where position(want in i.search_text) = 0
			              and not exists (
			                  select 1 from unnest(i.search_words) as word
			                   where length(word) between 1 and 64
			                     and abs(length(word) - length(want))
			                         <= case when length(want) <= 4 then 1 else 2 end
			                     and levenshtein_less_equal(word, want, 2)
			                         <= case when length(want) <= 4 then 1 else 2 end))
			),
			ranked as (
			    select m.group_key, min(a.sign * extract(epoch from m.activity_at)) as rank
			      from matching m
			      cross join args a
			     group by m.group_key
			),
			page as (
			    select r.group_key, r.rank
			      from ranked r
			      cross join args a
			     where cast(:cursorAt as text) is null
			        or (r.rank, r.group_key)
			           > (a.sign * extract(epoch from cast(:cursorAt as timestamptz)), cast(:cursorKey as text))
			     order by r.rank, r.group_key
			     limit :groups
			)
			select m.*
			  from matching m
			  join page p on p.group_key = m.group_key
			  cross join args a
			 order by p.rank, p.group_key, a.sign * extract(epoch from m.activity_at)
			""";

	private final EntityManager entityManager;

	FeedPageQuery(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * Every item of at most {@code groups} groups, in order.
	 *
	 * <p>The caller asks for one group more than it means to show, which is how it learns there is a
	 * next page without a second count query.
	 */
	List<FeedItem> rows(FeedRequest request, int groups) {
		FeedSearch search = request.search();
		FeedCursor cursor = request.cursor();

		return entityManager.unwrap(Session.class)
				.createNativeQuery(SQL, FeedItem.class)
				.setParameter("userId", request.userId())
				.setParameter("source", request.source() == null ? null : request.source().name())
				.setParameter("filter", request.filter().name())
				.setParameter("sign", request.order().sign())
				.setParameter("words", joined(search.words()))
				.setParameter("projects", joined(search.projects()))
				.setParameter("actors", joined(search.actors()))
				.setParameter("kinds", joined(search.kinds()))
				.setParameter("urlParts", joined(search.urlParts()))
				.setParameter("searchRead", search.read())
				// bound as text for the reason the cursor is: a timestamp reads back the same either way
				.setParameter("after", moment(search.after()))
				.setParameter("before", moment(search.before()))
				.setParameter("hasFiles", search.hasAttachment())
				.setParameter("cursorAt", cursor == null ? null : cursor.activityAt().toString())
				.setParameter("cursorKey", cursor == null ? null : cursor.groupKey())
				.setParameter("groups", groups)
				.getResultList();
	}

	/*
	 * one text parameter per list, split back apart with string_to_array, rather than binding a
	 * text[]: an empty or null array through a native query parameter is where the binding turns
	 * fragile. every value in these lists came from splitting the query on whitespace, so joining
	 * them back on a space loses nothing.
	 */
	private static String joined(List<String> terms) {
		return String.join(" ", terms);
	}

	private static String moment(Instant at) {
		return at == null ? null : at.toString();
	}
}
