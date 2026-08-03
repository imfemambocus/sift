package dev.emambocus.sift.config;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sift")
public record SiftProperties(
		@NotBlank(message = "sift.encryption-key is not set. Generate one with: openssl rand -base64 32")
		String encryptionKey,
		Registration registration) {

	public SiftProperties {
		if (registration == null) {
			registration = new Registration(List.of());
		}
	}

	public record Registration(List<String> allowedEmailDomains) {

		public Registration {
			allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
		}
	}
}
