package dev.emambocus.sift.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	// null once an account authenticates through an identity provider instead of a password
	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public User(String email, String displayName, String passwordHash) {
		this.email = email;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
	}
}
