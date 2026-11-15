package com.trustledger.core.kyc;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Test support: the KYC steps over HTTP, and fake document bytes with the right (or wrong) magic numbers. */
final class KycFlows {

    static final String ADDRESS = "12 Test Street, Indiranagar, Bengaluru 560038";

    /** Fake documents: correct signature, then filler. Never real documents. */
    static final byte[] PDF = bytes(new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A}, 64);
    static final byte[] JPEG = bytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 64);
    static final byte[] PNG = bytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    /** "MZ": a Windows executable, uploaded as pan.pdf with Content-Type application/pdf. */
    static final byte[] EXE = bytes(new byte[] {0x4D, 0x5A, (byte) 0x90, 0x00}, 64);

    private final MockMvc mvc;

    KycFlows(MockMvc mvc) {
        this.mvc = mvc;
    }

    static RequestPostProcessor officer(String subject) {
        return token(subject, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER");
    }

    /** A customer with approved minimum KYC (required before full KYC). */
    void minKyc(String subject) throws Exception {
        String phone = "7" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        String json = """
                {"fullName":"Kyc Full Person","dateOfBirth":"1990-06-01","mobile":"%s","pan":"ABCPE1234F"}"""
                .formatted(phone);
        mvc.perform(post("/api/v1/kyc/min").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated());
    }

    ResultActions submitFull(String subject, String address) throws Exception {
        return mvc.perform(post("/api/v1/kyc/full").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"" + address + "\"}"));
    }

    /** Minimum KYC, then full KYC; returns the FULL case ID. */
    UUID fullCase(String subject) throws Exception {
        minKyc(subject);
        String body = submitFull(subject, ADDRESS).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    ResultActions upload(String subject, UUID caseId, String type, byte[] content, String clientContentType)
            throws Exception {
        return mvc.perform(multipart("/api/v1/kyc/cases/{id}/documents", caseId)
                .file(new MockMultipartFile("file", "upload.bin", clientContentType, content))
                .param("type", type)
                .with(customer(subject)));
    }

    UUID uploadOk(String subject, UUID caseId, String type, byte[] content) throws Exception {
        String body = upload(subject, caseId, type, content, "application/octet-stream")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** A FULL case with both required documents, ready for the maker. */
    UUID readyCase(String subject) throws Exception {
        UUID caseId = fullCase(subject);
        uploadOk(subject, caseId, "PAN_CARD", PNG);
        uploadOk(subject, caseId, "ADDRESS_PROOF", PDF);
        return caseId;
    }

    ResultActions decide(String officerSubject, UUID caseId, String action) throws Exception {
        return mvc.perform(post("/api/v1/compliance/kyc-cases/{id}/" + action, caseId).with(officer(officerSubject)));
    }

    ResultActions reject(String officerSubject, UUID caseId, String reason) throws Exception {
        return mvc.perform(post("/api/v1/compliance/kyc-cases/{id}/reject", caseId).with(officer(officerSubject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code head} followed by distinct filler up to {@code size} bytes. */
    static byte[] bytes(byte[] head, int size) {
        byte[] out = Arrays.copyOf(head, size);
        byte[] filler = "fake-kyc-test-document;".getBytes(StandardCharsets.US_ASCII);
        for (int i = head.length; i < size; i++) {
            out[i] = filler[i % filler.length];
        }
        return out;
    }
}
