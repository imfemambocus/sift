package dev.emambocus.sift.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emambocus.sift.SiftIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * The half of the authorization code flow that is the same for every source. The rules here are
 * about the controller, not about any one provider: a state is single use, it is compared before
 * anything else happens, and one source's half-finished flow cannot satisfy another's callback.
 */
class SourceOAuthTest extends SiftIntegrationTest {

	@Autowired
	private SourceOAuthController controller;

	@Autowired
	private SourceService sources;

	@Test
	@DisplayName("a callback whose state does not match the session is discarded without an exchange")
	void mismatchedStateIsDiscarded() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("gitlab.oauth.state", "the-state-we-sent");
		session.setAttribute("gitlab.oauth.verifier", "the-verifier-we-kept");
		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.callback("gitlab", "a-code", "a-state-somebody-else-chose", null, session, response, null);

		assertThat(response.getRedirectedUrl()).isEqualTo("/settings?gitlab=denied");
		// and the state is spent either way, so a replay finds nothing waiting for it
		assertThat(session.getAttribute("gitlab.oauth.state")).isNull();
		assertThat(session.getAttribute("gitlab.oauth.verifier")).isNull();
	}

	@Test
	@DisplayName("one source's pending state cannot satisfy another source's callback")
	void statesDoNotCrossBetweenSources() throws Exception {
		MockHttpSession session = new MockHttpSession();
		// halfway through connecting GitLab, and a Gmail callback arrives carrying that state
		session.setAttribute("gitlab.oauth.state", "gitlab-state");
		session.setAttribute("gitlab.oauth.verifier", "gitlab-verifier");
		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.callback("gmail", "a-code", "gitlab-state", null, session, response, null);

		assertThat(response.getRedirectedUrl()).isEqualTo("/settings?gmail=denied");
		// and the GitLab flow it did not belong to is untouched, so that one can still finish
		assertThat(session.getAttribute("gitlab.oauth.state")).isEqualTo("gitlab-state");
	}

	@Test
	@DisplayName("a refused approval says so, and never carries what the provider wrote into the URL")
	void aRefusalRedirectsWithAFixedWord() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.callback("gmail", null, null, "access_denied by <script>", new MockHttpSession(), response, null);

		assertThat(response.getRedirectedUrl()).isEqualTo("/settings?gmail=denied");
	}

	@Test
	@DisplayName("every source that can be connected is offered, connected or not")
	void connectorsListsBothSources() {
		List<ConnectorResponse> connectors = sources.connectors(newUser("connectors@uni.lu"));

		assertThat(connectors).extracting(ConnectorResponse::source).containsExactly("gitlab", "gmail");
		assertThat(connectors).allMatch(ConnectorResponse::configured);
		// nothing is connected yet, which is exactly the case Home draws an invitation for
		assertThat(connectors).noneMatch(ConnectorResponse::connected);
	}
}
