package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.ExportStorage;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class DataExportProcessorTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
    private static final String KEY = "exports/" + USER + "/" + REQUEST + ".json";

    private final DataRequestWriter writer = mock(DataRequestWriter.class);
    private final DataExportAssembler assembler = mock(DataExportAssembler.class);
    private final ExportStorage storage = mock(ExportStorage.class);
    private final DataExportProcessor processor =
            new DataExportProcessor(writer, assembler, storage, JsonMapper.builder().build());

    @Test
    void storesTheJsonUnderTheRequestsKeyThenCompletes() {
        when(writer.find(REQUEST)).thenReturn(Optional.of(pending()));
        when(assembler.assemble(REQUEST, USER)).thenReturn(document());
        when(writer.completeExport(REQUEST)).thenReturn(true);

        processor.process(REQUEST);

        ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
        verify(storage).putJson(eq(KEY), json.capture());
        String body = new String(json.getValue(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(body, "$.format")).isEqualTo("trustledger-data-export/v1");
        assertThat((String) JsonPath.read(body, "$.requestId")).isEqualTo(REQUEST.toString());
        assertThat((String) JsonPath.read(body, "$.generatedAt")).isEqualTo("2026-11-22T10:00:00Z");
        assertThat(body).contains("\n"); // pretty-printed for the human reading it
        verify(writer).completeExport(REQUEST);
        verify(writer, never()).fail(any(), anyString());
    }

    @Test
    void aRequestThatIsNoLongerPendingIsSkipped() {
        DataRequest done = pending();
        done.completeExport(T0);
        when(writer.find(REQUEST)).thenReturn(Optional.of(done));

        processor.process(REQUEST);

        verifyNoInteractions(assembler, storage);
        verify(writer, never()).completeExport(any());
    }

    @Test
    void anUnknownRequestIsSkipped() {
        when(writer.find(REQUEST)).thenReturn(Optional.empty());

        processor.process(REQUEST);

        verifyNoInteractions(assembler, storage);
    }

    @Test
    void aStorageFailureMarksTheRequestFailedWithTheErrorCode() {
        when(writer.find(REQUEST)).thenReturn(Optional.of(pending()));
        when(assembler.assemble(REQUEST, USER)).thenReturn(document());
        doThrow(new TrustLedgerException(ErrorCode.STORAGE_UNAVAILABLE, "down"))
                .when(storage).putJson(anyString(), any());

        processor.process(REQUEST);

        verify(writer).fail(REQUEST, "STORAGE_UNAVAILABLE");
        verify(writer, never()).completeExport(any());
    }

    @Test
    void anUnexpectedFailureRecordsTheExceptionClassNeverItsMessage() {
        when(writer.find(REQUEST)).thenReturn(Optional.of(pending()));
        when(assembler.assemble(REQUEST, USER))
                .thenThrow(new IllegalStateException("customer.one@example.test 9876543210"));

        processor.process(REQUEST);

        verify(writer).fail(REQUEST, "IllegalStateException");
        verifyNoInteractions(storage);
    }

    @Test
    void aDeletedUserFailsTheExportAsNotFound() {
        when(writer.find(REQUEST)).thenReturn(Optional.of(pending()));
        when(assembler.assemble(REQUEST, USER))
                .thenThrow(new TrustLedgerException(ErrorCode.NOT_FOUND, "User not found."));

        processor.process(REQUEST);

        verify(writer).fail(REQUEST, "NOT_FOUND");
    }

    @Test
    void ifEvenRecordingTheFailureFailsTheExceptionEscapesSoTheEventIsRetried() {
        when(writer.find(REQUEST)).thenReturn(Optional.of(pending()));
        when(assembler.assemble(REQUEST, USER)).thenThrow(new IllegalStateException("boom"));
        when(writer.fail(REQUEST, "IllegalStateException")).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> processor.process(REQUEST)).hasMessage("database down");
    }

    private static DataRequest pending() {
        DataRequest request = DataRequest.export(USER, T0);
        try {
            Field field = DataRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(request, REQUEST);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return request;
    }

    private static DataExportDocument document() {
        return new DataExportDocument(DataExportDocument.FORMAT, REQUEST, USER, T0,
                new DataExportDocument.Profile("customer.one@example.test", "9876543210", "Test Customer One",
                        "ACTIVE", T0),
                List.of(), new DataExportDocument.Kyc("NONE", List.of()), null, List.of(), List.of());
    }
}
