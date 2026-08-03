package dev.emambocus.sift.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/*
 * spring boot hands hibernate a SpringBeanContainer, which is what makes constructor injection
 * work here: hibernate asks the application context for the converter instead of instantiating it
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

	private final TokenCipher cipher;

	public EncryptedStringConverter(TokenCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return attribute == null ? null : cipher.encrypt(attribute);
	}

	/*
	 * an unreadable value becomes null rather than an exception. it means the encryption key changed
	 * (or the column was tampered with), and throwing here would fail every read of the row,
	 * including the ones that never wanted the token: listing which sources are connected would 500
	 * instead of being able to tell the user to reconnect. callers treat a null token as "reconnect".
	 */
	@Override
	public String convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		try {
			return cipher.decrypt(dbData);
		}
		catch (RuntimeException ex) {
			log.warn("a stored token could not be decrypted, treating it as absent: {}", ex.getMessage());
			return null;
		}
	}
}
