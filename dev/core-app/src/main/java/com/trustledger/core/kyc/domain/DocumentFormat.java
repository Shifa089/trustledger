package com.trustledger.core.kyc.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The accepted file formats (FR-KYC-2), recognised by their magic bytes at offset 0. The file name and the
 * client's {@code Content-Type} are never trusted: renaming {@code virus.exe} to {@code pan.pdf} changes
 * neither its first bytes nor the result here.
 */
public enum DocumentFormat {

    /** {@code %PDF-}. */
    PDF("application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D}),
    /** SOI marker plus the first byte of the next marker: {@code FF D8 FF}. */
    JPEG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    /** The 8-byte PNG signature {@code 89 50 4E 47 0D 0A 1A 0A}. */
    PNG("image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

    private final String contentType;
    private final byte[] magic;

    DocumentFormat(String contentType, byte[] magic) {
        this.contentType = contentType;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    /** The format whose signature starts {@code content}; empty for anything else, including too-short input. */
    public static Optional<DocumentFormat> detect(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(format -> format.matches(content)).findFirst();
    }

    private boolean matches(byte[] content) {
        return content.length >= magic.length
                && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }
}
