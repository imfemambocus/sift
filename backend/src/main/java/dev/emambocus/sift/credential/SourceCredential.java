package dev.emambocus.sift.credential;

import dev.emambocus.sift.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "source_credentials")
@Getter
@Setter
@NoArgsConstructor
public class SourceCredential {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/*
	 * held as a plain id rather than a @ManyToOne: the sync sweep walks credentials without ever
	 * needing the owning user, and this keeps it clear of lazy loading with open-in-view disabled
	 */
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SourceType source;

	@Enumerated(EnumType.STRING)
	@Column(name = "credential_type", nullable = false)
	private CredentialType credentialType;

	@Column(name = "instance_url", nullable = false)
	private String instanceUrl;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "access_token_enc", nullable = false)
	private String accessToken;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "refresh_token_enc")
	private String refreshToken;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "last_sync_at")
	private Instant lastSyncAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_sync_status", nullable = false)
	private SyncStatus lastSyncStatus = SyncStatus.NEVER_RUN;

	@Column(name = "last_error")
	private String lastError;

	/** Which account at the source this is: a mailbox address, or a username. Null until a sweep runs. */
	@Column(name = "account_label")
	private String accountLabel;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public static SourceCredential oauth(UUID userId, SourceType source, String instanceUrl, String accessToken,
			String refreshToken, Instant expiresAt, Instant at) {
		SourceCredential credential = new SourceCredential();
		credential.userId = userId;
		credential.source = source;
		credential.credentialType = CredentialType.OAUTH;
		credential.instanceUrl = instanceUrl;
		credential.accessToken = accessToken;
		credential.refreshToken = refreshToken;
		credential.expiresAt = expiresAt;
		credential.createdAt = at;
		return credential;
	}

	/** Authorizing again over the same connection, which also clears the previous failure. */
	public void replaceWithOAuth(String instanceUrl, String accessToken, String refreshToken, Instant expiresAt) {
		this.credentialType = CredentialType.OAUTH;
		this.instanceUrl = instanceUrl;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
		this.lastSyncStatus = SyncStatus.NEVER_RUN;
		this.lastError = null;
	}

	/**
	 * Applies a renewal to this copy after it has been written. The sync outcome is not touched: a
	 * refresh happens in the middle of a sweep whose result is not known yet.
	 */
	public void applyRefreshedTokens(String accessToken, String refreshToken, Instant expiresAt) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
	}

	/*
	 * the sync outcome is written by a targeted update on the repository, not by mutating this entity
	 * and saving it: see the comment on recordSyncOutcome for why saving the whole row is unsafe here.
	 */

	// a stack-derived message can be enormous, and only the first line ever helps the user
	public static String abbreviateError(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "...";
	}

	private static final int MAX_ERROR_LENGTH = 500;
}
