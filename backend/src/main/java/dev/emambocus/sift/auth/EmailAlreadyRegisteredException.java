package dev.emambocus.sift.auth;

public class EmailAlreadyRegisteredException extends RuntimeException {

	public EmailAlreadyRegisteredException(String email) {
		super("An account already exists for " + email + ".");
	}
}
