package dev.emambocus.sift.feed;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Where the next page starts: the leading item of the last group that was handed out, and that
 * group's key to break a tie between two groups that last moved at the same moment.
 *
 * <p>A keyset rather than an offset, because the sweep is inserting and re-ordering rows underneath
 * the reader. An offset would repeat or skip a group every time something moved on page one.
 *
 * <p>Opaque to the client on purpose. It carries a timestamp rather than the rank the query sorts
 * by, so the rank arithmetic happens in the database on both sides of the comparison and there is no
 * way for a value rounded in Java to lose a group.
 */
public record FeedCursor(Instant activityAt, String groupKey) {

	private static final char SEPARATOR = '|';

	public String encode() {
		String plain = activityAt.toString() + SEPARATOR + groupKey;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
	}

	/** Null or blank is the first page. Anything else has to decode, or the request is a 400. */
	public static FeedCursor decode(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			return null;
		}
		String plain = decodeBase64(encoded);
		// the first separator, since a group key is a url and may hold one of its own
		int split = plain.indexOf(SEPARATOR);
		if (split < 0) {
			throw invalid();
		}
		return new FeedCursor(parseInstant(plain.substring(0, split)), plain.substring(split + 1));
	}

	private static String decodeBase64(String encoded) {
		try {
			return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
	}

	private static Instant parseInstant(String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw invalid();
		}
	}

	private static InvalidFeedRequestException invalid() {
		return new InvalidFeedRequestException("That page cursor is not one this feed handed out.");
	}
}
