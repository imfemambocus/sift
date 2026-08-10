package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.security.SiftUserDetails;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

	private final SourceService sources;

	public SourceController(SourceService sources) {
		this.sources = sources;
	}

	@GetMapping
	public List<SourceStatusResponse> list(@AuthenticationPrincipal SiftUserDetails principal) {
		return sources.statuses(principal.id());
	}

	/** What could be connected, which is what Home offers. {@code GET /api/sources} answers only what is. */
	@GetMapping("/connectors")
	public List<ConnectorResponse> connectors(@AuthenticationPrincipal SiftUserDetails principal) {
		return sources.connectors(principal.id());
	}

	@PostMapping("/{source}/sync")
	public SourceStatusResponse sync(@PathVariable String source,
			@AuthenticationPrincipal SiftUserDetails principal) {

		SourceType type = sources.resolve(source);
		return sources.syncNow(principal.id(), type)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That source is not connected."));
	}

	/**
	 * Forgets how much of the source has been read, so the next read walks all of it again. The rows
	 * stay where they are and fill back in, which is what separates this from disconnecting.
	 */
	@PostMapping("/{source}/reread")
	public SourceStatusResponse reread(@PathVariable String source,
			@AuthenticationPrincipal SiftUserDetails principal) {

		SourceType type = sources.resolve(source);
		return sources.reread(principal.id(), type)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That source is not connected."));
	}

	@DeleteMapping("/{source}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disconnect(@PathVariable String source, @AuthenticationPrincipal SiftUserDetails principal) {
		SourceType type = sources.resolve(source);
		if (!sources.disconnect(principal.id(), type)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That source is not connected.");
		}
	}
}
