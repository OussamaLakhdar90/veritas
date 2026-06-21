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

    @Value("${veritas.http.powershell-fallback:false}")
    private boolean powershellFallback;

    @Value("${veritas.http.powershell-timeout-seconds:60}")
    private long powershellTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public CorpHttp(Retries retries,
                    @Value("${veritas.http.connect-timeout-ms:10000}") int connectTimeoutMs,
                    @Value("${veritas.http.read-timeout-ms:60000}") int readTimeoutMs) {
        this.retries = retries;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);   // bounded so a hung host fails fast (PowerShell fallback can engage)
        factory.setReadTimeout(readTimeoutMs);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Convenience for tests: default bounded timeouts. */
    public CorpHttp(Retries retries) {
        this(retries, 10000, 60000);
    }

    public String get(String url, Map<String, String> headers) {
        return exec("GET", url, headers, null, null);
    }

    public String post(String url, Map<String, String> headers, String body, String contentType) {
        return exec("POST", url, headers, body, contentType);
    }

    private String exec(String method, String url, Map<String, String> headers, String body, String contentType) {
        try {
            return retries.call(() -> {
                RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method)).uri(URI.create(url));
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
            });
        } catch (RuntimeException e) {
            if (powershellFallback) {
                log.warn("{} {} via RestClient failed ({}); trying PowerShell fallback", method, url, e.getMessage());
                return viaPowerShell(method, url, headers, body, contentType);
            }
            throw e;
        }
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
            Process p = pb.redirectErrorStream(false).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(powershellTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("PowerShell HTTP timed out");
            }
            return out.toString().trim();
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
