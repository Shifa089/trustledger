package com.trustledger.core.payments.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body is read up front (at most {@code limit + 1} bytes), so the idempotency interceptor can hash
 * it <em>before</em> the controller reads it. Spring's {@code ContentCachingRequestWrapper} only caches what the
 * controller has already consumed, which is too late.
 * <p>
 * A body above the limit is not truncated: the rest is streamed from the original request, and
 * {@link #exceedsLimit()} tells the interceptor to refuse it.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] prefix;
    private final boolean exceedsLimit;
    private boolean streamTaken;

    CachedBodyRequest(HttpServletRequest request, int limit) throws IOException {
        super(request);
        this.prefix = request.getInputStream().readNBytes(limit + 1);
        this.exceedsLimit = prefix.length > limit;
    }

    boolean exceedsLimit() {
        return exceedsLimit;
    }

    /** The whole body; only meaningful when it is within the limit. */
    byte[] body() {
        if (exceedsLimit) {
            throw new IllegalStateException("The body exceeds the idempotency body limit");
        }
        return prefix.clone();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        InputStream rest = exceedsLimit && !streamTaken ? super.getInputStream() : InputStream.nullInputStream();
        streamTaken = true;
        return new BytesInputStream(new SequenceInputStream(new ByteArrayInputStream(prefix), rest));
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    private static final class BytesInputStream extends ServletInputStream {

        private final InputStream delegate;
        private boolean finished;

        BytesInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            finished = b < 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            finished = n < 0;
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Async reads are not supported");
        }
    }
}
