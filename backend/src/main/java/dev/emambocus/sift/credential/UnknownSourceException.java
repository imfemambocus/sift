package dev.emambocus.sift.credential;

/** Lives next to {@link SourceType} because both the feed and source management resolve slugs. */
public class UnknownSourceException extends RuntimeException {

	public UnknownSourceException(String requested) {
		super("There is no source called '%s'. Known sources: %s.".formatted(requested, SourceType.known()));
	}
}
