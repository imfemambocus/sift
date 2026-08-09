package dev.emambocus.sift.sync;

import java.util.Set;

/**
 * What a source itself says about reading, in the ids Sift stores its rows under.
 *
 * <p>Two sets rather than one flag per id, because the two directions are applied by different
 * statements: a row that is already read must keep the time it was read at, and one that is already
 * unread must not be given a time at all.
 *
 * <p>{@code unreadIsComplete} says {@code unread} is every unread row the source holds, so every
 * other row of that source has been read. A source answers that only when it can prove it, and it is
 * how a connection recovers after losing the point it was reading changes from.
 */
public record SourceReadState(Set<String> read, Set<String> unread, boolean unreadIsComplete) {

	public static final SourceReadState NONE = new SourceReadState(Set.of(), Set.of(), false);

	public SourceReadState {
		read = Set.copyOf(read);
		unread = Set.copyOf(unread);
	}

	public static SourceReadState changed(Set<String> read, Set<String> unread) {
		return new SourceReadState(read, unread, false);
	}

	/** Everything the source still counts as unread, which makes the rest of it read. */
	public static SourceReadState only(Set<String> unread) {
		return new SourceReadState(Set.of(), unread, true);
	}

	public boolean isEmpty() {
		return !unreadIsComplete && read.isEmpty() && unread.isEmpty();
	}
}
