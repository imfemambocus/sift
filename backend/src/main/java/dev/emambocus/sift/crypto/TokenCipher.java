package dev.emambocus.sift.crypto;

import dev.emambocus.sift.config.SiftProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts source access tokens before they reach the database. Ciphertext is
 * {@code base64(iv || ciphertext || tag)}, with a fresh IV per call.
 */
@Component
public class TokenCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int KEY_BYTES = 32;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public TokenCipher(SiftProperties properties) {
		byte[] raw = decodeKey(properties.encryptionKey());
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException(
					"sift.encryption-key must be base64 of exactly %d bytes, decoded to %d".formatted(KEY_BYTES, raw.length));
		}
		this.key = new SecretKeySpec(raw, "AES");
	}

	public String encrypt(String plaintext) {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] combined = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
			return Base64.getEncoder().encodeToString(combined);
		}
		catch (GeneralSecurityException ex) {
			// never let the plaintext travel with the failure
			throw new IllegalStateException("failed to encrypt token", ex);
		}
	}

	public String decrypt(String encoded) {
		byte[] combined = Base64.getDecoder().decode(encoded);
		if (combined.length <= IV_BYTES) {
			throw new IllegalStateException("stored token is too short to be valid ciphertext");
		}
		byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
		byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("failed to decrypt token; sift.encryption-key may have changed", ex);
		}
	}

	private static byte[] decodeKey(String encoded) {
		try {
			return Base64.getDecoder().decode(encoded.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("sift.encryption-key is not valid base64", ex);
		}
	}
}
