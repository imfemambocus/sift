package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.security.SiftUserDetails;
import dev.emambocus.sift.sync.SourceReadSync;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
public class FeedController {

	private static final int DEFAULT_GROUPS = 50;
	private static final int MAX_GROUPS = 500;

	private final FeedService feed;
	private final SourceReadSync readSync;

	public FeedController(FeedService feed, SourceReadSync readSync) {
		this.feed = feed;
		this.readSync = readSync;
	}

	/**
	 * One page of the feed. No {@code source} is every source, which is what the search asks for.
	 *
	 * <p>{@code limit} counts groups rather than items, so a merge request's four rows always arrive
	 * together. {@code cursor} is what the previous page handed back, and is opaque.
	 */
	@GetMapping
	public FeedPageResponse feed(@RequestParam(required = false) String source,
			@RequestParam(required = false) String filter,
			@RequestParam(required = false) String order,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			@AuthenticationPrincipal SiftUserDetails principal) {

		return feed.page(new FeedRequest(principal.id(), parse(source), FeedFilter.parse(filter),
				FeedOrder.parse(order), FeedSearch.parse(q), FeedCursor.decode(cursor), bounded(limit)));
	}

	/** The counts behind every number the app shows without showing the rows it counted. */
	@GetMapping("/summary")
	public List<FeedSummaryResponse> summary(@AuthenticationPrincipal SiftUserDetails principal) {
		return feed.summary(principal.id());
	}

	/*
	 * a ceiling as well as a default, since the page size is a promise about how much work one
	 * request can ask for. the verification suites are what want the large end of it.
	 */
	private static int bounded(Integer limit) {
		if (limit == null) {
			return DEFAULT_GROUPS;
		}
		return Math.clamp(limit, 1, MAX_GROUPS);
	}

	/** Absent or blank means every source, which is what Home asks for. */
	private static SourceType parse(String source) {
		if (source == null || source.isBlank()) {
			return null;
		}
		return SourceType.parse(source).orElseThrow(() -> new UnknownSourceException(source));
	}

	/**
	 * Clears everything still unread, for one source or for all of them.
	 *
	 * <p>A POST to its own path rather than a PATCH on the collection: it is an action with one
	 * direction, and a collection PATCH would invite "mark everything unread", which nobody wants and
	 * which would need its own query to support.
	 */
	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void readAll(@RequestParam(required = false) String source,
			@AuthenticationPrincipal SiftUserDetails principal) {

		List<SourceRow> cleared = feed.markAllRead(principal.id(), parse(source));
		pushRead(principal.id(), cleared, true);
	}

	/**
	 * Marks one item read or unread. Nothing else about an item is editable, so the body is one
	 * field; it is a PATCH rather than two verbs because unread is a real thing to want back.
	 */
	@PatchMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void update(@PathVariable UUID id, @Valid @RequestBody UpdateFeedItemRequest body,
			@AuthenticationPrincipal SiftUserDetails principal) {

		SourceRow row = feed.setRead(principal.id(), id, body.read());
		pushRead(principal.id(), List.of(row), body.read());
	}

	/*
	 * outside the service on purpose. the decision is already written, and this is a network call,
	 * which must never be made with a transaction open.
	 */
	private void pushRead(UUID userId, List<SourceRow> rows, boolean read) {
		rows.stream()
				.collect(Collectors.groupingBy(SourceRow::source,
						Collectors.mapping(SourceRow::sourceId, Collectors.toList())))
				.forEach((source, sourceIds) -> readSync.push(userId, source, sourceIds, read));
	}
}
