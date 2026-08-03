package dev.emambocus.sift.sync;

/** The credential was rejected. Retrying on a schedule will not fix it; the user must reconnect. */
public class SourceAuthException extends RuntimeException {

	public SourceAuthException(String message) {
		super(message);
	}
}
