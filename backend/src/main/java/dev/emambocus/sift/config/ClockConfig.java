package dev.emambocus.sift.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ClockConfig {

	/** Injected rather than calling {@code Instant.now()} inline, so sync timing can be tested. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
