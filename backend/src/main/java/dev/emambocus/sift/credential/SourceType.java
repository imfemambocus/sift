package dev.emambocus.sift.credential;

import java.util.Locale;
import java.util.Optional;

public enum SourceType {

	GITLAB;

	/** Lowercase form used in URLs and API responses, so callers never see SCREAMING_CASE. */
	public String slug() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Optional<SourceType> parse(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		String trimmed = value.trim();
		for (SourceType type : values()) {
			if (type.name().equalsIgnoreCase(trimmed)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	public static String known() {
		StringBuilder names = new StringBuilder();
		for (SourceType type : values()) {
			if (!names.isEmpty()) {
				names.append(", ");
			}
			names.append(type.slug());
		}
		return names.toString();
	}
}
