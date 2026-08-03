package dev.emambocus.sift.sources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConnectSourceRequest(
		@NotBlank @Size(max = 500) String instanceUrl,
		@NotBlank @Size(max = 500) String token) {
}
