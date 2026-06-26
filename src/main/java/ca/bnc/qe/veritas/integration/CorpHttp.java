package ca.bnc.qe.veritas.integration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP transport for BNC-facing JSON calls: RestClient first (with {@link Retries}), and — when
 * {@code veritas.http.powershell-fallback=true} — a PowerShell {@code Invoke-RestMethod} fallback that
 * inherits the system proxy/TLS/firewall rules. This mirrors the contract-validator app, whose Rust HTTP was
 * blocked by the corporate firewall while {@code powershell.exe} was allowed (see docs/reference-...md).
 */
@Component
@Slf4j
public class CorpHttp {

    private final Retries retries;
    private final RestClient http;
    private final RestClient longHttp;   // longer read timeout for LLM calls (generation can exceed the REST timeout)

    /** Optional: present when actuator/Micrometer is on the classpath; null in unit tests that construct CorpHttp bare. */
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Value("${veritas.http.powershell-fallback:false}")
    private boolean powershellFallback;

    @Value("${veritas.http.powershell-timeout-seconds:60}")
    private long powershellTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public CorpHttp(Retries retries,
                    @Value("${veritas.http.connect-timeout-ms:10000}") int connectTimeoutMs,
                    @Value("${veritas.http.read-timeout-ms:60000}") int readTimeoutMs,
                    @Value("${veritas.llm.http-read-timeout-ms:180000}") int llmReadTimeoutMs) {
        this.retries = retries;
        this.http = client(connectTimeoutMs, readTimeoutMs);
        this.longHttp = client(connectTimeoutMs, llmReadTimeoutMs);
    }

    private static RestClient client(int connectTimeoutMs, int readTimeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);   // bounded so a hung host fails fast (PowerShell fallback can engage)
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Convenience for tests: default bounded timeouts. */
    public CorpHttp(Retries retries) {
        this(retries, 10000, 60000, 180000);
    }

    public String get(String url, Map<String, String> headers) {
        return exec(http, "GET", url, headers, null, null, false);
    }

    /** Idempotent POST (e.g. a Jira search) — safe to retry/replay on any transient failure. */
    public String post(String url, Map<String, String> headers, String body, String contentType) {
        return exec(http, "POST", url, headers, body, contentType, false);
    }

    /**
     * Non-idempotent write POST (createIssue/createTest/addStep/comment/link). Retried only on a connection failure
     * (request never sent); never replayed on a 5xx/read-timeout, so a write can't be duplicated in the bank's tracker.
     */
    public String postWrite(String url, Map<String, String> headers, String body, String contentType) {
        return exec(http, "POST", url, headers, body, contentType, true);
    }

    /** POST with the longer read timeout — for LLM/Copilot calls whose generation can take minutes. */
    public String postLong(String url, Map<String, String> headers, String body, String contentType) {
        return exec(longHttp, "POST", url, headers, body, contentType, false);
    }

    /**
     * POST and consume the response body line-by-line (for an SSE/streaming LLM response). The read timeout
     * then applies <em>between</em> chunks rather than to the whole body, so a multi-minute generation that
     * streams tokens no longer trips a single whole-body read timeout. Not retried — a half-consumed stream
     * can't be safely replayed.
     */
    public void postStreamLines(String url, Map<String, String> headers, String body, String contentType,
                                java.util.function.Consumer<String> lineConsumer) {
        RestClient.RequestBodySpec req = longHttp.method(HttpMethod.POST).uri(URI.create(url));
        if (headers != null) {
            req.headers(h -> headers.forEach((k, v) -> {
                if (v != null) {
                    h.set(k, v);
                }
            }));
        }
        if (contentType != null) {
            req.header("Content-Type", contentType);
        }
        if (body != null) {
            req.body(body);
        }
        req.exchange((request, response) -> {
            try (var in = response.getBody();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                if (response.getStatusCode().isError()) {
                    StringBuilder err = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null && err.length() < 500) {
                        err.append(l);
                    }
                    throw new IllegalStateException("HTTP " + response.getStatusCode().value() + " from " + url
                            + (err.length() == 0 ? "" : ": " + err));
                }
                String line;
                while ((line = r.readLine()) != null) {
                    lineConsumer.accept(line);
                }
            }
            return null;
        });
    }

    private String exec(RestClient client, String method, String url, Map<String, String> headers, String body,
                        String contentType, boolean write) {
        java.util.function.Supplier<String> op = () -> {
            RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(URI.create(url));
            if (headers != null) {
                spec = spec.headers(h -> headers.forEach((k, v) -> {
                    if (v != null) {
                        h.set(k, v);
                    }
                }));
            }
            if (contentType != null) {
                spec = spec.header("Content-Type", contentType);
            }
            if (body != null) {
                spec = spec.body(body);
            }
            return spec.retrieve().body(String.class);
        };
        long start = System.nanoTime();
        try {
            String result = write ? retries.callWrite(op) : retries.call(op);
            metric(method, write, "success", start);
            log.debug("{} {} -> ok in {} ms", method, url, elapsedMs(start));
            return result;
        } catch (RuntimeException e) {
            // The PowerShell fallback RE-SENDS the request. For a non-idempotent write that's only safe when the
            // RestClient attempt never reached the server (a connection failure) — never on a 5xx/read-timeout,
            // which could duplicate the write. Idempotent calls (GET / search POST / LLM) may always fall back.
            if (powershellFallback && (!write || Retries.isConnectFailure(e))) {
                log.warn("{} {} via RestClient failed ({}); trying PowerShell fallback", method, url, e.getMessage());
                return viaPowerShell(method, url, headers, body, contentType);
            }
            metric(method, write, "error", start);
            log.warn("{} {} failed after {} ms: {}", method, url, elapsedMs(start), e.getMessage());
            throw e;
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Per-event integration-call timer (tagged method/outcome/write) — no-op when no registry is wired. */
    private void metric(String method, boolean write, String outcome, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        io.micrometer.core.instrument.Timer.builder("veritas.integration.http")
                .tag("method", method).tag("outcome", outcome).tag("write", Boolean.toString(write))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private String viaPowerShell(String method, String url, Map<String, String> headers, String body, String contentType) {
        try {
            java.util.List<String> keys = new java.util.ArrayList<>(headers == null ? java.util.Map.<String, String>of().keySet() : headers.keySet());
            String script = buildPowerShellScript(method, url, keys, body != null, contentType);
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            // Secrets (Authorization header values, request body) go via ENV, never the command line (process list).
            for (int i = 0; i < keys.size(); i++) {
                String v = headers.get(keys.get(i));
                pb.environment().put("VERITAS_HDR_" + i, v == null ? "" : v);
            }
            if (body != null) {
                pb.environment().put("VERITAS_BODY", body);
            }
            // Merge stderr into stdout (one stream, no second pipe to deadlock on) and drain it on a separate
            // thread so a large/blocked output never wedges the process before waitFor can enforce the timeout.
            Process p = pb.redirectErrorStream(true).start();
            java.util.concurrent.CompletableFuture<String> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
                return out.toString();
            });
            try {
                if (!p.waitFor(powershellTimeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PowerShell HTTP timed out after " + powershellTimeoutSeconds + "s");
                }
                return reader.get(5, TimeUnit.SECONDS).trim();
            } finally {
                if (p.isAlive()) {
                    p.destroyForcibly();   // never leak the child on timeout/error/success
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PowerShell HTTP fallback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the Invoke-RestMethod script. Header VALUES and the body are read from environment variables
     * ($env:VERITAS_HDR_i / $env:VERITAS_BODY) so secrets never appear on the command line. Header KEYS are
     * not sensitive. Package-visible + pure → unit-tested without executing PowerShell.
     */
    static String buildPowerShellScript(String method, String url, java.util.List<String> headerKeys,
                                        boolean hasBody, String contentType) {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < headerKeys.size(); i++) {
            h.append("'").append(headerKeys.get(i).replace("'", "''")).append("'=$env:VERITAS_HDR_").append(i).append("; ");
        }
        StringBuilder s = new StringBuilder();
        s.append("[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $ProgressPreference='SilentlyContinue'; ");
        s.append("$r = Invoke-RestMethod -Uri '").append(url.replace("'", "''"))
                .append("' -Method ").append(method).append(" -Headers @{ ").append(h).append("}");
        if (hasBody) {
            s.append(" -ContentType '").append(contentType == null ? "application/json" : contentType)
                    .append("' -Body $env:VERITAS_BODY");
        }
        s.append("; if ($r -is [string]) { $r } else { $r | ConvertTo-Json -Depth 10 -Compress }");
        return s.toString();
    }
}
