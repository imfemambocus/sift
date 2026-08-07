package dev.emambocus.sift;

import dev.emambocus.sift.user.User;
import dev.emambocus.sift.user.UserRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres behind a real application context, which is the only way these tests are worth
 * anything: the unique key on {@code (user_id, source, source_id)}, the {@code jsonb} column, Flyway
 * and {@code ddl-auto: validate} are all things an in-memory database would fake or skip.
 *
 * <p>The container is static, so one is started for the whole test run rather than one per class, and
 * every subclass declares the same {@code @SpringBootTest} properties so Spring caches a single
 * context across all of them.
 */
@SpringBootTest(properties = {
		"sift.encryption-key=dGVzdC1rZXktdGhpcnR5LXR3by1ieXRlcy1sb25nISE=",
		// the sweep must never fire mid-test: these tests drive the sync themselves
		"sift.sync.initial-delay=PT2H",
		"sift.sync.interval=PT2H",
		/*
		 * an OAuth application, so the flow is configured. the instance URL is a placeholder: a
		 * refresh goes to the credential's own instance, which is the stand-in on an ephemeral port.
		 */
		"sift.gitlab.oauth.instance-url=https://gl.example.org",
		"sift.gitlab.oauth.client-id=sift-under-test",
		"sift.gitlab.oauth.client-secret=not-a-real-secret",
		"sift.gitlab.oauth.redirect-uri=http://localhost:7777/api/sources/gitlab/oauth/callback",
})
// every user-owned table cascades from users, so one delete is the whole reset
@Sql(statements = "delete from users", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class SiftIntegrationTest {

	/*
	 * started by hand and never stopped, rather than with @Container. JUnit's container lifecycle is
	 * per class, but Spring caches one context for the whole run, so the second test class inherited a
	 * datasource pointing at a container the first class had already stopped: every test after the
	 * first class failed on a refused connection, each one after a thirty-second pool timeout.
	 * Testcontainers' own Ryuk sidecar removes it when the JVM exits.
	 */
	@ServiceConnection
	// the same image compose runs, so a migration that passes here passes there
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
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
