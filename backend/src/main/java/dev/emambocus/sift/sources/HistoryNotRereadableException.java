package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceType;

/**
 * The source is connected, and it holds nothing that reading again would fill in.
 *
 * <p>Its own answer rather than the "not connected" one, because the two want different things from
 * whoever asked: one is a source to connect, the other is a source that already has all it can hold.
 */
public class HistoryNotRereadableException extends RuntimeException {

	public HistoryNotRereadableException(SourceType source) {
		super("Sift reads all of " + source.slug() + " on every pass, so there is no history to read again.");
	}
}
