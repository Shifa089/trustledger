package com.trustledger.core.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts a personal-data column at rest. Use explicitly on each field:
 * {@code @Convert(converter = EncryptedStringConverter.class) private String panEnc;}
 * <p>
 * Spring Boot lets Hibernate obtain converters from the Spring context, so the cipher is injected.
 * Encrypted columns cannot be searched; pair them with a {@link BlindIndex} column when lookups
 * are needed.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmCipher cipher;

    public EncryptedStringConverter(AesGcmCipher cipher) {
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
