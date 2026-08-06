package dev.emambocus.sift.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A search box query, taken apart into the pieces the page query can ask the database for.
 *
 * <p>One search over everything, however you half remember it. This is one of the two complaints the
 * app started from: Outlook held the mail but could not find anything in it.
 *
 * <p>Every token has to match, so {@code is:mr is:unread} narrows and {@code project:a project:b}
 * finds nothing.
 *
 * @param words free text, lowercased. Each one has to appear, in any order, and one typo in each is
 *     forgiven by the query.
 * @param projects values of {@code project:}, matched against the context label
 * @param actors values of {@code from:}, matched against the actor name
 * @param kinds values of {@code is:} that name no known shape, matched against the source's own
 *     action token, so {@code is:merged} and {@code is:thread} work without being listed anywhere
 * @param urlParts what {@code is:mr} and {@code is:issue} become. The url is what reliably says which
 *     of the two something is, across every kind of row.
 * @param read what {@code is:read} and {@code is:unread} become, null when neither was typed
 * @param impossible both {@code is:read} and {@code is:unread} were typed. Every token has to match,
 *     so nothing can, and the service answers an empty page rather than letting one of them win.
 */
public record FeedSearch(
		List<String> words,
		List<String> projects,
		List<String> actors,
		List<String> kinds,
		List<String> urlParts,
		Boolean read,
		boolean impossible) {

	public static final FeedSearch NONE =
			new FeedSearch(List.of(), List.of(), List.of(), List.of(), List.of(), null, false);

	private static final Pattern PREFIX = Pattern.compile("^(is|project|from):(.+)$", Pattern.CASE_INSENSITIVE);

	public static FeedSearch parse(String query) {
		if (query == null || query.isBlank()) {
			return NONE;
		}
		Tokens tokens = new Tokens();
		for (String token : query.trim().split("\\s+")) {
			tokens.add(token);
		}
		return tokens.toSearch();
	}

	/** Collects one token at a time, so no single method has to hold the whole grammar. */
	private static final class Tokens {

		private final List<String> words = new ArrayList<>();
		private final List<String> projects = new ArrayList<>();
		private final List<String> actors = new ArrayList<>();
		private final List<String> kinds = new ArrayList<>();
		private final List<String> urlParts = new ArrayList<>();
		private Boolean read;
		private boolean impossible;

		void add(String token) {
			Matcher prefixed = PREFIX.matcher(token);
			if (!prefixed.matches()) {
				words.add(lower(token));
				return;
			}
			String value = lower(prefixed.group(2));
			switch (lower(prefixed.group(1))) {
				case "project" -> projects.add(value);
				case "from" -> actors.add(value);
				default -> addShape(value);
			}
		}

		private void addShape(String value) {
			switch (value) {
				case "mr" -> urlParts.add("/merge_requests/");
				case "issue" -> urlParts.add("/issues/");
				case "unread" -> wantRead(false);
				case "read" -> wantRead(true);
				default -> kinds.add(value);
			}
		}

		private void wantRead(boolean wanted) {
			if (read != null && read != wanted) {
				impossible = true;
			}
			read = wanted;
		}

		FeedSearch toSearch() {
			return new FeedSearch(List.copyOf(words), List.copyOf(projects), List.copyOf(actors),
					List.copyOf(kinds), List.copyOf(urlParts), read, impossible);
		}

		private static String lower(String value) {
			return value.toLowerCase(Locale.ROOT);
		}
	}
}
