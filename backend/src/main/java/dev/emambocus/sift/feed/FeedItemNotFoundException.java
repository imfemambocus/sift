package dev.emambocus.sift.feed;

import java.util.UUID;

/** Also what an item belonging to another user looks like, deliberately: it does not exist for you. */
public class FeedItemNotFoundException extends RuntimeException {

	public FeedItemNotFoundException(UUID id) {
		super("No feed item " + id + ".");
	}
}
