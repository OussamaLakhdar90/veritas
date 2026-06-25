package ca.bnc.qe.veritas.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Mints stable, citable {@link EvidenceUnit} ids. The section/unit id embeds a hash of the unit's <b>own</b>
 * normalised text, so it survives reordering or insertion elsewhere in the source — the positional counter it
 * replaces ({@code #slug-N}) renumbered every unit below an edit, silently invalidating every cached id. Here,
 * unchanged content keeps its id; only edited content re-mints. See design §2.
 */
public final class EvidenceId {

    private EvidenceId() {
    }

    /** Jira issue-level: the issue key is already a stable natural key. */
    public static String issue(String issueKey) {
        return issueKey;
    }

    /** A Jira sub-element (acceptance criterion, etc.): issue key + a hash of its own text. */
    public static String jiraPart(String issueKey, String text) {
        return issueKey + "#" + hash8(text);
    }

    /** A Confluence section: page id + heading slug + content hash (stable when sections are reordered). */
    public static String section(String sourceId, String heading, String text) {
        return sourceId + "#" + slug(heading) + "-" + hash8(text);
    }

    /** A code endpoint: declaring class + HTTP method + path — unique and derivable (no method-name collisions). */
    public static String endpoint(String className, String httpMethod, String path) {
        return "CODE:" + className + "#" + upper(httpMethod) + " " + path;
    }

    /** A code DTO field constraint: class + field. */
    public static String dtoConstraint(String className, String field) {
        return "CODE:" + className + "." + field;
    }

    /** A pre-authored policy/standard control (e.g. {@code POLICY:owasp-api4-rate-limiting}). */
    public static String policy(String catalog, String control) {
        return "POLICY:" + slug(catalog) + "-" + slug(control);
    }

    /** A global static-analysis caveat surfaced from {@code ApiModel.blindSpots()}. */
    public static String caveat(String text) {
        return "CODE:caveat-" + hash8(text);
    }

    /**
     * Normalise text so trivial whitespace/case reformatting doesn't re-mint an id, while a real content change
     * does. (Strip, collapse internal whitespace to single spaces, lowercase.)
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * First 8 lowercase base32 chars of SHA-256 over the normalised text (40 bits → exactly 8 chars). Public so the
     * feature index can derive stable {@code featureId}s and a {@code sourceDigest} from the same scheme.
     */
    public static String hash8(String text) {
        return base32(sha256(normalize(text)), 5);
    }

    /**
     * URL/citation-safe slug: diacritics folded (so {@code Café}→{@code cafe}, keeping French headings readable),
     * lowercased, non-alphanumerics collapsed to single hyphens, trimmed.
     */
    static String slug(String s) {
        if (s == null || s.isBlank()) {
            return "x";
        }
        String folded = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String out = folded.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return out.isEmpty() ? "x" : out;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final char[] B32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    /** RFC 4648 base32 (lowercase, no padding) over the first {@code nBytes} bytes of {@code data}. */
    private static String base32(byte[] data, int nBytes) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < nBytes && i < data.length; i++) {
            buffer = (buffer << 8) | (data[i] & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(B32[(buffer >> bits) & 0x1F]);
            }
        }
        if (bits > 0) {
            sb.append(B32[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }
}
