package dev.emambocus.sift.feed;

/**
 * Both orders are on {@code activity_at}, which is a short list on purpose rather than for lack of
 * ideas: the client groups by day and merges only consecutive rows carrying the same label, so a sort
 * on any other field repeats "Today" down the page.
 *
 * <p>Oldest first is not a curiosity. On a list of things waiting on you, the one that has waited
 * longest is the one you are most likely neglecting.
 */
public enum FeedOrder {

	LATEST(-1), WAITING(1);

	private final int sign;

	FeedOrder(int sign) {
		this.sign = sign;
	}

	/**
	 * What the page query multiplies each timestamp by, so one expression covers both directions.
	 *
	 * <p>A group ranks at {@code min(sign * epoch(activity_at))} over its items. With a sign of 1 that
	 * is the oldest item, and with a sign of -1 it is the negated newest one, because
	 * {@code -max(x) = min(-x)}. Everything downstream then sorts ascending, so the keyset comparison
	 * that walks the cursor forward is written once instead of once per direction.
	 */
	public int sign() {
		return sign;
	}

	public static FeedOrder parse(String value) {
		if (value == null || value.isBlank()) {
			return LATEST;
		}
		for (FeedOrder order : values()) {
			if (order.name().equalsIgnoreCase(value.trim())) {
				return order;
			}
		}
		throw new InvalidFeedRequestException("Unknown order '" + value + "'. Use latest or waiting.");
	}
}
