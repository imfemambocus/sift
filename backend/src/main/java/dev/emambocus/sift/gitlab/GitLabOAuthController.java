package dev.emambocus.sift.gitlab;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.security.SiftUserDetails;
import dev.emambocus.sift.sources.SourceCredentialStore;
import dev.emambocus.sift.sync.FeedSyncService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two ends of the authorization code flow, plus the question the settings page asks before it
 * decides which connect button to draw.
 *
 * <p>Authorizing a source is not signing in to Sift. There is no identity here: the person is
 * already signed in, and the callback is authenticated like every other API path, which is what ties
 * the returned code to the account that started the flow.
 */
@RestController
@RequestMapping("/api/sources/gitlab/oauth")
class GitLabOAuthController {

	private static final Logger log = LoggerFactory.getLogger(GitLabOAuthController.class);

	private static final String STATE_ATTRIBUTE = "gitlab.oauth.state";
	private static final String VERIFIER_ATTRIBUTE = "gitlab.oauth.verifier";

	private static final String SETTINGS = "/settings";

	private final GitLabOAuth oauth;
	private final SourceCredentialStore store;
	private final FeedSyncService syncService;

	GitLabOAuthController(GitLabOAuth oauth, SourceCredentialStore store, FeedSyncService syncService) {
		this.oauth = oauth;
		this.store = store;
		this.syncService = syncService;
	}

	/** @param instanceUrl null when nothing is configured, so the page can name the instance it offers */
	record Availability(boolean configured, String instanceUrl) {
	}

	record Authorization(String authorizeUrl) {
	}

	@GetMapping
	Availability availability() {
		return new Availability(oauth.isConfigured(), oauth.isConfigured() ? oauth.instanceUrl() : null);
	}

	/**
	 * A POST rather than a redirect of its own, so the browser leaves the app under its own control
	 * and the call is covered by CSRF like every other state-changing one.
	 */
	@PostMapping("/start")
	Authorization start(HttpSession session) {
		requireConfigured();

		String state = oauth.newSecret();
		String verifier = oauth.newSecret();
		session.setAttribute(STATE_ATTRIBUTE, state);
		session.setAttribute(VERIFIER_ATTRIBUTE, verifier);

		return new Authorization(oauth.authorizeUrl(state, verifier));
	}

	/**
	 * Where GitLab sends the browser back to. It answers a redirect and never JSON, because what
	 * arrives here is a top-level navigation and not a call the app made.
	 */
	@GetMapping("/callback")
	void callback(@RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String error,
			HttpSession session,
			HttpServletResponse response,
			@AuthenticationPrincipal SiftUserDetails principal) throws IOException {

		String expectedState = (String) session.getAttribute(STATE_ATTRIBUTE);
		String verifier = (String) session.getAttribute(VERIFIER_ATTRIBUTE);
		// single use, whatever happens next: a replayed code must not find a state waiting for it
		session.removeAttribute(STATE_ATTRIBUTE);
		session.removeAttribute(VERIFIER_ATTRIBUTE);

		if (error != null) {
			log.info("the GitLab authorization was not granted: {}", error);
			response.sendRedirect(SETTINGS + "?gitlab=denied");
			return;
		}
		if (code == null || verifier == null || !matches(state, expectedState)) {
			log.warn("discarded a GitLab callback with no code or an unexpected state");
			response.sendRedirect(SETTINGS + "?gitlab=denied");
			return;
		}

		GitLabResponses.OAuthToken token;
		try {
			token = oauth.exchange(code, verifier);
		}
		catch (RuntimeException ex) {
			log.warn("could not exchange the GitLab authorization code: {}", ex.getMessage());
			response.sendRedirect(SETTINGS + "?gitlab=failed");
			return;
		}

		SourceCredential credential = store.upsertOAuth(principal.id(), SourceType.GITLAB, oauth.instanceUrl(),
				token.accessToken(), token.refreshToken(), oauth.expiryOf(token));

		/*
		 * read once now so the feed is already populated when the browser lands. a failure here is
		 * recorded on the credential itself, so the settings page shows the real reason: reporting it
		 * twice would mean inventing a second wording for the same thing.
		 */
		try {
			syncService.sync(credential);
		}
		catch (RuntimeException ex) {
			log.warn("connected GitLab but the first read failed: {}", ex.getMessage());
		}

		response.sendRedirect(SETTINGS);
	}

	private void requireConfigured() {
		if (!oauth.isConfigured()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"This Sift has no GitLab OAuth application configured.");
		}
	}

	private static boolean matches(String state, String expected) {
		if (state == null || expected == null) {
			return false;
		}
		return MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}
}
