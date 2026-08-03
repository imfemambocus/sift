package dev.emambocus.sift.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/*
 * spring boot hands hibernate a SpringBeanContainer, which is what makes constructor injection
 * work here: hibernate asks the application context for the converter instead of instantiating it
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private final TokenCipher cipher;

	public EncryptedStringConverter(TokenCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return attribute == null ? null : cipher.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		return dbData == null ? null : cipher.decrypt(dbData);
	}
}
