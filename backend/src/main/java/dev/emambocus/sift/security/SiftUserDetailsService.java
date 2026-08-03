package dev.emambocus.sift.security;

import dev.emambocus.sift.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiftUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	public SiftUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) {
		return users.findByEmailIgnoreCase(email)
				// an account with no password hash authenticates through an identity provider, so
				// it must not be reachable down the password path
				.filter(user -> user.getPasswordHash() != null)
				.map(user -> new SiftUserDetails(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash()))
				.orElseThrow(() -> new UsernameNotFoundException("no password account for " + email));
	}
}
