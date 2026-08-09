package dev.emambocus.sift.feed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * @param after what {@code after:} becomes: nothing older than this moment, compared against the
 *     activity the list already orders and dates by. Null when it was not typed.
 * @param before what {@code before:} becomes: nothing from this moment onwards
 * @param hasAttachment what {@code has:attachment} becomes, null when it was not typed
 * @param impossible two tokens ask for opposite things, or a date cannot be read. Every token has to
 *     match, so nothing can, and the service answers an empty page rather than letting one win.
 */
public record FeedSearch(
		List<String> words,
		List<String> projects,
		List<String> actors,
		List<String> kinds,
		List<String> urlParts,
		Boolean read,
		Instant after,
		Instant before,
		Boolean hasAttachment,
		boolean impossible) {

	public static final FeedSearch NONE = new FeedSearch(List.of(), List.of(), List.of(), List.of(),
			List.of(), null, null, null, null, false);

	private static final Pattern PREFIX =
			Pattern.compile("^(is|project|from|has|after|before):(.+)$", Pattern.CASE_INSENSITIVE);

	/**
	 * Takes a query apart against the moment it was typed, which {@code after:7d} and the rest of the
	 * relative spans are measured back from.
	 */
	public static FeedSearch parse(String query, Instant now) {
		if (query == null || query.isBlank()) {
			return NONE;
		}
		Tokens tokens = new Tokens(now);
		for (String token : query.trim().split("\\s+")) {
			tokens.add(token);
		}
		return tokens.toSearch();
	}

	/** Collects one token at a time, so no single method has to hold the whole grammar. */
	private static final class Tokens {

		/** A span back from now: a number and one of hours, days, weeks, months or years. */
		private static final Pattern SPAN = Pattern.compile("^(\\d{1,4})([hdwmy])$");

		private static final Set<String> FILES = Set.of("attachment", "attachments", "file", "files");

		private final Instant now;
		private final List<String> words = new ArrayList<>();
		private final List<String> projects = new ArrayList<>();
		private final List<String> actors = new ArrayList<>();
		private final List<String> kinds = new ArrayList<>();
		private final List<String> urlParts = new ArrayList<>();
		private Boolean read;
		private Instant after;
		private Instant before;
		private Boolean hasAttachment;
		private boolean impossible;

		Tokens(Instant now) {
			this.now = now;
		}

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
				case "has" -> addHas(value);
				case "after" -> addAfter(value);
				case "before" -> addBefore(value);
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

		/** Files are the only thing a row can have, so anything else asks for something nothing has. */
		private void addHas(String value) {
			if (FILES.contains(value)) {
				hasAttachment = true;
				return;
			}
			impossible = true;
		}

		// two of them narrow to one window, so the latest floor and the earliest ceiling win
		private void addAfter(String value) {
			Instant moment = momentOf(value);
			if (moment == null) {
				impossible = true;
			}
			else if (after == null || moment.isAfter(after)) {
				after = moment;
			}
		}

		private void addBefore(String value) {
			Instant moment = momentOf(value);
			if (moment == null) {
				impossible = true;
			}
			else if (before == null || moment.isBefore(before)) {
				before = moment;
			}
		}

		/**
		 * A calendar date, or a span back from now. Null when it is neither, which makes the search
		 * impossible: a half typed date must answer nothing rather than everything.
		 */
		private Instant momentOf(String value) {
			Matcher span = SPAN.matcher(value);
			if (span.matches()) {
				return ago(Integer.parseInt(span.group(1)), span.group(2).charAt(0));
			}
			try {
				// a date names a day, and a day is read in UTC, so one query means one thing everywhere
				return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
			}
			catch (DateTimeParseException ex) {
				return null;
			}
		}

		private Instant ago(int amount, char unit) {
			ZonedDateTime from = now.atZone(ZoneOffset.UTC);
			return switch (unit) {
				case 'h' -> from.minusHours(amount).toInstant();
				case 'd' -> from.minusDays(amount).toInstant();
				case 'w' -> from.minusWeeks(amount).toInstant();
				case 'm' -> from.minusMonths(amount).toInstant();
				default -> from.minusYears(amount).toInstant();
			};
		}

		FeedSearch toSearch() {
			return new FeedSearch(List.copyOf(words), List.copyOf(projects), List.copyOf(actors),
					List.copyOf(kinds), List.copyOf(urlParts), read, after, before, hasAttachment,
					impossible);
		}

		private static String lower(String value) {
			return value.toLowerCase(Locale.ROOT);
		}
	}
}
