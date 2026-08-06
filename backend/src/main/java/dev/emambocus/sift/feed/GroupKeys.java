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
	 * one rule for every source; a source that needs its own answer (a mail conversation id) is what
	 * would move this onto IncomingItem.
	 *
	 * stored on the row rather than computed in the response, because the feed pages over groups and
	 * the database is what does that grouping. V8 carries the same rule once, to backfill.
	 */
	public static String of(SourceType source, String url) {
		int fragment = url.indexOf('#');
		return source.slug() + ":" + (fragment < 0 ? url : url.substring(0, fragment));
	}
}
