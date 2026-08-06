package dev.emambocus.sift.feed;

/** A parameter of a feed request the server cannot make sense of. Answered as a 400. */
public class InvalidFeedRequestException extends RuntimeException {

	public InvalidFeedRequestException(String message) {
		super(message);
	}
}
