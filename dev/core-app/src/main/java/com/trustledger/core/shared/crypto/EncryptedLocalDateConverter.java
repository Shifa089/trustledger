package com.trustledger.core.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Encrypts a date column at rest (e.g. date of birth, FR-PR-1). The date is stored as the AES-GCM
 * ciphertext of its ISO-8601 form ({@code 1990-04-15}), so the entity keeps a real {@link LocalDate}.
 */
@Component
@Converter
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

    private final AesGcmCipher cipher;

    public EncryptedLocalDateConverter(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : cipher.encrypt(attribute.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData == null ? null : LocalDate.parse(cipher.decrypt(dbData));
    }
}
