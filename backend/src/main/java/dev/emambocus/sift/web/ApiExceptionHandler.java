package dev.emambocus.sift.web;

import dev.emambocus.sift.auth.EmailAlreadyRegisteredException;
import dev.emambocus.sift.auth.EmailDomainNotAllowedException;
import dev.emambocus.sift.credential.UnknownSourceException;
import dev.emambocus.sift.feed.FeedItemNotFoundException;
import dev.emambocus.sift.feed.InvalidFeedRequestException;
import dev.emambocus.sift.sources.InvalidSourceUrlException;
import dev.emambocus.sift.sync.SourceAuthException;
import dev.emambocus.sift.sync.SourceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	public ProblemDetail handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(EmailDomainNotAllowedException.class)
	public ProblemDetail handleEmailDomainNotAllowed(EmailDomainNotAllowedException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ProblemDetail handleBadCredentials() {
		// deliberately does not distinguish an unknown address from a wrong password, since that
		// difference is enough to enumerate who has an account
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
	}

	@ExceptionHandler({ InvalidSourceUrlException.class, UnknownSourceException.class,
			InvalidFeedRequestException.class })
	public ProblemDetail handleBadSourceRequest(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(FeedItemNotFoundException.class)
	public ProblemDetail handleFeedItemNotFound(FeedItemNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	/** The request was well formed; the credential in it was not accepted by the source. */
	@ExceptionHandler(SourceAuthException.class)
	public ProblemDetail handleSourceAuth(SourceAuthException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
	}

	/** Nothing wrong with the request or the token: the source itself could not be reached. */
	@ExceptionHandler(SourceUnavailableException.class)
	public ProblemDetail handleSourceUnavailable(SourceUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
	}
}
