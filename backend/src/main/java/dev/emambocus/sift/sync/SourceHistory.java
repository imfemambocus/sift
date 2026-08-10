package dev.emambocus.sift.sync;

import java.time.Instant;

/**
 * How much of a source's history is here, which only a source that cannot read all of it in one
 * sweep has anything to say about.
 *
 * @param complete false while there is older history still to read
 * @param readBackTo how far back the reading has reached, so a page can name it rather than leaving
 *     a short list unexplained. Null for a source that has read nothing yet, and for one that has
 *     no history to walk.
 * @param stalled true when successive reads stopped reaching anything older, which is what a source
 *     refusing the pace looks like from outside the log
 * @param rereadable true when the source can be told to read its history again from the beginning
 */
public record SourceHistory(boolean complete, Instant readBackTo, boolean stalled, boolean rereadable) {

	/** A source that reads everything it holds on every sweep, which is most of them. */
	public static final SourceHistory COMPLETE = new SourceHistory(true, null, false, false);
}
