package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.security.SiftUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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

	private final FeedService feed;

	public FeedController(FeedService feed) {
		this.feed = feed;
	}

	/** No {@code source} is Home: everything, from every source, newest first. */
	@GetMapping
	public List<FeedItemResponse> feed(@RequestParam(required = false) String source,
			@AuthenticationPrincipal SiftUserDetails principal) {

		return feed.feed(principal.id(), parse(source));
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

		feed.markAllRead(principal.id(), parse(source));
	}

	/**
	 * Marks one item read or unread. Nothing else about an item is editable, so the body is one
	 * field; it is a PATCH rather than two verbs because unread is a real thing to want back.
	 */
	@PatchMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void update(@PathVariable UUID id, @Valid @RequestBody UpdateFeedItemRequest body,
			@AuthenticationPrincipal SiftUserDetails principal) {

		feed.setRead(principal.id(), id, body.read());
	}
}
