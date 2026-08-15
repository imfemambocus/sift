package dev.emambocus.sift;

import dev.emambocus.sift.gmail.FakeGmail;
import dev.emambocus.sift.user.User;
import dev.emambocus.sift.user.UserRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres behind a real application context, which is the only way these tests are worth
 * anything: the unique key on {@code (user_id, source, source_id)}, the {@code jsonb} column, Flyway
 * and {@code ddl-auto: validate} are all things an in-memory database would fake or skip.
 *
 * <p>The container is static: one is started for the whole test run rather than one per class. Every
 * subclass declares the same {@code @SpringBootTest} properties, which is what makes Spring cache a
 * single context across all of them.
 */
@SpringBootTest(properties = {
		"sift.encryption-key=dGVzdC1rZXktdGhpcnR5LXR3by1ieXRlcy1sb25nISE=",
		// the sweep must never fire mid-test: these tests drive the sync themselves
		"sift.sync.initial-delay=PT2H",
		"sift.sync.interval=PT2H",
		/*
		 * an OAuth application, which is what makes the flow configured. the instance URL is a
		 * placeholder: a refresh goes to the credential's own instance, the stand-in on its own port.
		 */
		"sift.gitlab.oauth.instance-url=https://gl.example.org",
		"sift.gitlab.oauth.client-id=sift-under-test",
		"sift.gitlab.oauth.client-secret=not-a-real-secret",
		"sift.gitlab.oauth.redirect-uri=http://localhost:7777/api/sources/gitlab/oauth/callback",
		// and a Gmail client: both flows configured, and the connectors list complete
		"sift.gmail.client-id=sift-mail-under-test",
		"sift.gmail.client-secret=not-a-real-secret-either",
		"sift.gmail.redirect-uri=http://localhost:7777/api/sources/gmail/oauth/callback",
})
// every user-owned table cascades from users: one delete is the whole reset
@Sql(statements = "delete from users", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class SiftIntegrationTest {

	/*
	 * started by hand and never stopped, rather than with @Container. JUnit's container lifecycle is
	 * per class where Spring caches one context for the whole run, so a per-class container leaves
	 * every later class holding a datasource that points at a stopped one. each of their tests then
	 * fails on a refused connection, thirty seconds of pool timeout apiece. Testcontainers' own Ryuk
	 * sidecar removes the container when the JVM exits.
	 */
	@ServiceConnection
	// the same image compose runs: a migration that passes here passes there
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	/*
	 * static for the same reason the container is. Google's three hosts are configuration rather than
	 * something a credential carries. Pointing the source at a stand-in means setting a property, and
	 * a property that differed per class would build a second application context. One server for the
	 * whole run, reset by whichever test uses it.
	 */
	protected static final FakeGmail GMAIL = new FakeGmail();

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void gmailEndpoints(DynamicPropertyRegistry registry) {
		registry.add("sift.gmail.base-url", GMAIL::baseUrl);
	}

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	protected UUID newUser(String email) {
		return users.save(new User(email, "Test", "{noop}unused")).getId();
	}

	/** For the handful of assertions that have to see the column rather than the mapped value. */
	protected JdbcTemplate jdbc() {
		return jdbcTemplate;
	}
}
