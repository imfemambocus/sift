package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.security.SiftUserDetails;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
}
