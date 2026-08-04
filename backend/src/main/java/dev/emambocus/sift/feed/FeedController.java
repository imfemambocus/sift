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

		SourceType type = null;
		if (source != null && !source.isBlank()) {
			type = SourceType.parse(source).orElseThrow(() -> new UnknownSourceException(source));
		}
		return feed.feed(principal.id(), type);
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
