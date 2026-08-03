package dev.emambocus.sift.auth;

import java.util.List;

public class EmailDomainNotAllowedException extends RuntimeException {

	public EmailDomainNotAllowedException(List<String> allowedDomains) {
		super("Registration is limited to addresses at: " + String.join(", ", allowedDomains) + ".");
	}
}
