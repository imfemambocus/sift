package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;

/**
 * What a row is about, so the several events one merge request produces collapse into one entry
 * instead of repeating its title down the list. Opaque to the client: only equality means anything.
 */
public final class GroupKeys {

	private GroupKeys() {
	}

	/*
	 * the url without its fragment, which is what several rows about one thing have in common: a
	 * mention lands on #note_998, a reply on #note_1002, and the review request on the page itself.
	 *
	 * stored on the row rather than computed in the response, because the feed pages over groups and
	 * the database is what does that grouping. V8 carries the same rule once, to backfill.
	 */
	public static String of(SourceType source, String url) {
		int fragment = url.indexOf('#');
		return source.slug() + ":" + (fragment < 0 ? url : url.substring(0, fragment));
	}

	/**
	 * For a source that knows what its rows are about, which the rule above cannot work out. Gmail is
	 * why: every message of every thread lives at the same path, and only the fragment tells them
	 * apart, so stripping the fragment would make the whole mailbox one group.
	 */
	public static String ofConversation(SourceType source, String conversationId) {
		return source.slug() + ":thread:" + conversationId;
	}
}
