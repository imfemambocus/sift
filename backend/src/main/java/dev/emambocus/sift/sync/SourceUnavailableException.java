package dev.emambocus.sift.sync;

/** The source could not be reached or answered badly. Worth retrying on the next sweep. */
public class SourceUnavailableException extends RuntimeException {

	public SourceUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

	public SourceUnavailableException(String message) {
		super(message);
	}
}
