package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.security.SiftUserDetails;
import dev.emambocus.sift.sync.FeedSyncService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two ends of the authorization code flow, for whichever source is being connected.
 *
 * <p>Authorizing a source is not signing in to Sift. There is no identity here: the person is
 * already signed in, and the callback is authenticated like every other API path, which is what ties
 * the returned code to the account that started the flow.
 *
 * <p>A redirect URI is registered with the provider. Renaming these paths means registering every
 * application again.
 */
@RestController
@RequestMapping("/api/sources/{source}/oauth")
class SourceOAuthController {

	private static final Logger log = LoggerFactory.getLogger(SourceOAuthController.class);

	/*
	 * where a person lands afterwards, and the two are deliberately different. success goes to Home,
	 * which is where the source has a card with its counts on it and where the offer to connect sits.
	 * a failure goes to Settings, since that is the only page that renders the reason and holds the
	 * instructions for fixing it.
	 */
	private static final String HOME = "/";
	private static final String SETTINGS = "/settings";

	private static final int SECRET_BYTES = 32;

	private final Map<SourceType, SourceOAuthFlow> flows;
	private final SourceService sources;
	private final SourceCredentialStore store;
	private final FeedSyncService syncService;
	private final SecureRandom random = new SecureRandom();

	SourceOAuthController(List<SourceOAuthFlow> flows, SourceService sources, SourceCredentialStore store,
			FeedSyncService syncService) {

		this.flows = flows.stream()
				.collect(Collectors.toUnmodifiableMap(SourceOAuthFlow::source, Function.identity()));
		this.sources = sources;
		this.store = store;
		this.syncService = syncService;
	}

	/** @param target null when nothing is configured. It is how the page names what it offers */
	record Availability(boolean configured, String target) {
	}

	record Authorization(String authorizeUrl) {
	}

	@GetMapping
	Availability availability(@PathVariable String source) {
		SourceOAuthFlow flow = flow(source);
		return new Availability(flow.configured(), flow.configured() ? flow.target() : null);
	}

	/**
	 * A POST rather than a redirect of its own. The browser leaves the app under its own control, and
	 * the call is covered by CSRF like every other state-changing one.
	 */
	@PostMapping("/start")
	Authorization start(@PathVariable String source, HttpSession session) {
		SourceOAuthFlow flow = flow(source);
		requireConfigured(flow);

		String state = newSecret();
		String verifier = newSecret();
		// keyed by source: authorizing one while another is half-started cannot cross the two
		session.setAttribute(stateAttribute(flow), state);
		session.setAttribute(verifierAttribute(flow), verifier);

		return new Authorization(flow.authorizeUrl(state, verifier));
	}

	/**
	 * Where the provider sends the browser back to. It answers a redirect and never JSON, because
	 * what arrives here is a top-level navigation and not a call the app made. The query carries a
	 * fixed word rather than a message: nothing a remote server wrote is put in this app's URL.
	 */
	@GetMapping("/callback")
	void callback(@PathVariable String source,
			@RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String error,
			HttpSession session,
			HttpServletResponse response,
			@AuthenticationPrincipal SiftUserDetails principal) throws IOException {

		SourceOAuthFlow flow = flow(source);
		String slug = flow.source().slug();

		String expectedState = (String) session.getAttribute(stateAttribute(flow));
		String verifier = (String) session.getAttribute(verifierAttribute(flow));
		// single use, whatever happens next: a replayed code must not find a state waiting for it
		session.removeAttribute(stateAttribute(flow));
		session.removeAttribute(verifierAttribute(flow));

		if (error != null) {
			log.info("the {} authorization was not granted: {}", slug, error);
			response.sendRedirect(SETTINGS + "?" + slug + "=denied");
			return;
		}
		if (code == null || verifier == null || !matches(state, expectedState)) {
			log.warn("discarded a {} callback with no code or an unexpected state", slug);
			response.sendRedirect(SETTINGS + "?" + slug + "=denied");
			return;
		}

		OAuthTokens tokens;
		try {
			tokens = flow.exchange(code, verifier);
		}
		catch (RuntimeException ex) {
			log.warn("could not exchange the {} authorization code: {}", slug, ex.getMessage());
			response.sendRedirect(SETTINGS + "?" + slug + "=failed");
			return;
		}

		SourceCredential credential = store.upsertOAuth(principal.id(), flow.source(), flow.accountUrl(),
				tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());

		/*
		 * the first read starts here and the browser does not wait for it. a mailbox is minutes of
		 * sequential requests: reading before the redirect means a blank page for as long as it takes.
		 * what the app needs to say meanwhile is already built. the source reports itself as syncing,
		 * and its counts fill in as the rows land. a failure is recorded on the credential itself,
		 * which is what the settings page reads the real reason out of.
		 */
		syncService.syncInBackground(credential);

		response.sendRedirect(HOME);
	}

	private SourceOAuthFlow flow(String slug) {
		SourceOAuthFlow flow = flows.get(sources.resolve(slug));
		if (flow == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That source is not connected by approval.");
		}
		return flow;
	}

	private static void requireConfigured(SourceOAuthFlow flow) {
		if (!flow.configured()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"This Sift has no %s application configured.".formatted(flow.source().slug()));
		}
	}

	private String newSecret() {
		byte[] bytes = new byte[SECRET_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String stateAttribute(SourceOAuthFlow flow) {
		return flow.source().slug() + ".oauth.state";
	}

	private static String verifierAttribute(SourceOAuthFlow flow) {
		return flow.source().slug() + ".oauth.verifier";
	}

	private static boolean matches(String state, String expected) {
		if (state == null || expected == null) {
			return false;
		}
		return MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}
}
