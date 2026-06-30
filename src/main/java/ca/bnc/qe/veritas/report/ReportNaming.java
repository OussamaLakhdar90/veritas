package ca.bnc.qe.veritas.report;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import ca.bnc.qe.veritas.persistence.Scan;

/**
 * Human-readable base name for a scan's on-disk report artifacts:
 * {@code contract-report-<service>-<model>-<yyyy-MM-dd_HHmmss>} instead of an opaque scan UUID. Derived purely from the
 * scan's own fields, so the writer ({@code ContractValidationService}) and the fallback reader ({@code ReportController})
 * always agree on the name without persisting it. Each component is slugged to a safe filename charset
 * ({@code [A-Za-z0-9_-]}; dots and separators collapse to '-'), so the name can never contain a path separator or
 * {@code ..}. The timestamp is UTC for deterministic reconstruction and disambiguates same-day re-scans.
 */
public final class ReportNaming {

    private ReportNaming() {
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneOffset.UTC);

    /** {@code contract-report-<service>-<model>-<yyyy-MM-dd_HHmmss>} (no extension). */
    public static String baseName(Scan scan) {
        String service = slug(scan.getServiceName(), "service");
        String model = slug(scan.getModel(), "no-ai");
        String date = scan.getStartedAt() == null ? "undated" : STAMP.format(scan.getStartedAt());
        return "contract-report-" + service + "-" + model + "-" + date;
    }

    /** A safe filename token: keep {@code [A-Za-z0-9_-]}, collapse everything else (incl. '.', '/', spaces) to '-',
     *  trim leading/trailing '-', cap length, and fall back when the result is blank. */
    private static String slug(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim().replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            return fallback;
        }
        return s.length() > 48 ? s.substring(0, 48) : s;
    }
}
