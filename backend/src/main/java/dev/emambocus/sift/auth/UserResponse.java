package dev.emambocus.sift.auth;

import dev.emambocus.sift.security.SiftUserDetails;
import dev.emambocus.sift.user.User;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
	}

	public static UserResponse from(SiftUserDetails principal) {
		return new UserResponse(principal.id(), principal.email(), principal.displayName());
	}
}
