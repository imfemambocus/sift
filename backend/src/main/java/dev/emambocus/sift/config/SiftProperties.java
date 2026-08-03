package dev.emambocus.sift.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sift")
public record SiftProperties(
		@NotBlank(message = "sift.encryption-key is not set. Generate one with: openssl rand -base64 32")
		String encryptionKey,
		Registration registration,
		Sync sync) {

	public SiftProperties {
		if (registration == null) {
			registration = new Registration(List.of());
		}
		if (sync == null) {
			sync = new Sync(null, null, 0);
		}
	}

	public record Registration(List<String> allowedEmailDomains) {

		public Registration {
			allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
		}
	}

	/**
	 * {@code interval} is duplicated as a property placeholder on the scheduled method, because an
	 * annotation cannot read a bound record. This copy exists so the interval can be logged.
	 */
	public record Sync(Duration interval, Duration initialDelay, int maxPages) {

		private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);
		private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(30);
		private static final int DEFAULT_MAX_PAGES = 20;

		public Sync {
			if (interval == null) {
				interval = DEFAULT_INTERVAL;
			}
			if (initialDelay == null) {
				initialDelay = DEFAULT_INITIAL_DELAY;
			}
			if (maxPages <= 0) {
				maxPages = DEFAULT_MAX_PAGES;
			}
		}
	}
}
