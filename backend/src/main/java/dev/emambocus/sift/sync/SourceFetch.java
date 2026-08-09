package dev.emambocus.sift.sync;

import java.util.List;
import java.util.Set;

/**
 * One read of a source: the rows it produced, what the source says about reading, which rows have
 * left the source altogether, and what to write down once all of that is stored.
 *
 * <p>A source that remembers how far it has read must not write that down inside the read itself.
 * The next sweep starts after whatever the last one recorded, so a failure between the two loses
 * every row of that read for ever. {@code commit} runs after the rows are in the database, which
 * makes a failed sweep read the same messages again instead of stepping over them.
 *
 * <p>{@code gone} is not the same as an item the source stopped reporting, which is what
 * {@code IncomingItem.resolveWhenAbsent} answers. Resolving means finished; this means the source no
 * longer holds the thing at all, so there is nothing left for a row to be about.
 */
public record SourceFetch(
		List<IncomingItem> items,
		SourceReadState readState,
		Set<String> gone,
		Runnable commit) {

	private static final Runnable NOTHING_TO_COMMIT = () -> {
		// a source that keeps nothing between sweeps has nothing to write down
	};

	public SourceFetch {
		gone = Set.copyOf(gone);
	}

	public static SourceFetch of(List<IncomingItem> items) {
		return new SourceFetch(items, SourceReadState.NONE, Set.of(), NOTHING_TO_COMMIT);
	}

	public static SourceFetch of(List<IncomingItem> items, Runnable commit) {
		return new SourceFetch(items, SourceReadState.NONE, Set.of(), commit);
	}
}
