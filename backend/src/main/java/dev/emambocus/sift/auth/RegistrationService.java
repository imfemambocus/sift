package dev.emambocus.sift.auth;

import dev.emambocus.sift.config.SiftProperties;
import dev.emambocus.sift.user.User;
import dev.emambocus.sift.user.UserRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final List<String> allowedEmailDomains;

	public RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, SiftProperties properties) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.allowedEmailDomains = properties.registration().allowedEmailDomains();
	}

	@Transactional
	public User register(RegisterRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		requireAllowedDomain(email);
		if (users.existsByEmailIgnoreCase(email)) {
			throw new EmailAlreadyRegisteredException(email);
		}
		String passwordHash = passwordEncoder.encode(request.password());
		return users.save(new User(email, request.displayName().trim(), passwordHash));
	}

	// an empty allowlist means anything goes, which is only appropriate for a local instance
	private void requireAllowedDomain(String email) {
		if (allowedEmailDomains.isEmpty()) {
			return;
		}
		String domain = email.substring(email.indexOf('@') + 1);
		boolean allowed = allowedEmailDomains.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(domain));
		if (!allowed) {
			throw new EmailDomainNotAllowedException(allowedEmailDomains);
		}
	}
}
