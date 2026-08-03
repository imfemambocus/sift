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

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public static SourceCredential personalAccessToken(UUID userId, SourceType source, String instanceUrl,
			String token, Instant at) {
		SourceCredential credential = new SourceCredential();
		credential.userId = userId;
		credential.source = source;
		credential.credentialType = CredentialType.PERSONAL_ACCESS_TOKEN;
		credential.instanceUrl = instanceUrl;
		credential.accessToken = token;
		credential.createdAt = at;
		return credential;
	}

	/** Reconnecting clears the previous failure, so the sweep starts picking it up again. */
	public void replacePersonalAccessToken(String instanceUrl, String token) {
		this.credentialType = CredentialType.PERSONAL_ACCESS_TOKEN;
		this.instanceUrl = instanceUrl;
		this.accessToken = token;
		this.refreshToken = null;
		this.expiresAt = null;
		this.lastSyncStatus = SyncStatus.NEVER_RUN;
		this.lastError = null;
	}

	public void recordSuccess(Instant at) {
		this.lastSyncAt = at;
		this.lastSyncStatus = SyncStatus.OK;
		this.lastError = null;
	}

	public void recordFailure(Instant at, SyncStatus status, String message) {
		this.lastSyncAt = at;
		this.lastSyncStatus = status;
		this.lastError = abbreviate(message);
	}

	// a stack-derived message can be enormous, and only the first line ever helps the user
	private static String abbreviate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "...";
	}

	private static final int MAX_ERROR_LENGTH = 500;
}
