package ca.bnc.qe.veritas.evidence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic PII/secret redaction applied to evidence text <b>before</b> it is placed in an
 * {@link EvidenceUnit} — and therefore before it can reach the LLM. The synthesis path feeds full evidence text
 * to the model, and neither {@code PromptComposer} (injection-defang + budget-trim) nor {@code LogMasker}
 * (logs only) redact it; for a regulated bank, ingesting developer-pasted tickets unredacted is approval-blocking.
 *
 * <p>Covers: payment card numbers (Luhn-gated), Canadian SIN (Luhn-gated), email, IPv4, {@code bearer}/{@code basic}
 * auth headers, JWTs, {@code key: value} secret assignments, and high-signal bare cloud/VCS tokens (AWS/GitHub/Google/
 * OpenAI/Slack/PEM). <b>Hostnames are deliberately NOT redacted</b> — generic hostname matching is too high-false-positive
 * for prose (every documented service name would be scrubbed).
 *
 * <p><b>Non-silent</b>: {@link Result#count()} reports how many spans were redacted so the total can be surfaced
 * for QE attestation (design §2.1). Pattern-based defence-in-depth — not a guarantee; novel secret formats can slip,
 * which is why the count is shown rather than the redaction being assumed complete.
 */
public final class Redactor {

    /** The redacted text and how many spans were replaced. */
    public record Result(String text, int count) {
    }

    private Redactor() {
    }

    // Structured/most-specific first so a token isn't half-matched by a looser pattern. All patterns are written
    // to avoid catastrophic backtracking: no two adjacent sub-expressions overlap on the same character class.
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\b");
    /** High-signal, fixed-prefix credentials that appear bare (not in {@code key: value} form). Low false-positive. */
    private static final Pattern CLOUD_TOKEN = Pattern.compile(
            "\\bAKIA[0-9A-Z]{16}\\b"                       // AWS access key id
            + "|\\bgh[pousr]_[0-9A-Za-z]{36,}\\b"          // GitHub PAT / OAuth / app tokens
            + "|\\bAIza[0-9A-Za-z_-]{35}\\b"               // Google API key
            + "|\\bsk-[A-Za-z0-9]{20,}\\b"                 // OpenAI-style secret key
            + "|\\bxox[baprs]-[0-9A-Za-z-]{10,}\\b"        // Slack token
            + "|-----BEGIN [A-Z ]*PRIVATE KEY-----");      // PEM private-key header
    private static final Pattern AUTH_HEADER =
            Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SECRET_ASSIGN =
            Pattern.compile("(?i)\\b(?:api[_-]?key|secret|token|password|passwd|pwd)\\b\\s*[:=]\\s*\\S+");
    // Label-structured domain ((?:label\.)+TLD) with bounded quantifiers — removes the dot/letter overlap that
    // makes the naive `[A-Za-z0-9.-]+\.[A-Za-z]{2,}` form backtrack quadratically on a long failing `@`-run.
    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,24}\\b");
    private static final Pattern IPV4 =
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    // A 13–19 digit run (optionally space/hyphen grouped) that passes the Luhn check → likely a card number.
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d[\\d -]{11,21}\\d\\b");
    // A 9-digit run (optionally grouped 3-3-3) — Luhn-gated, so it only fires on real Canadian SINs, not phone numbers.
    private static final Pattern SIN = Pattern.compile("\\b\\d{3}[ -]?\\d{3}[ -]?\\d{3}\\b");

    public static Result redact(String text) {
        if (text == null || text.isBlank()) {
            return new Result(text, 0);
        }
        int[] count = {0};
        String out = text;
        out = replace(out, JWT, "[REDACTED-TOKEN]", count);
        out = replace(out, CLOUD_TOKEN, "[REDACTED-SECRET]", count);
        out = replace(out, AUTH_HEADER, "[REDACTED-AUTH]", count);
        out = replace(out, SECRET_ASSIGN, "[REDACTED-SECRET]", count);
        out = replaceLuhnGated(out, LONG_NUMBER, 13, 19, "[REDACTED-PAN]", count);
        out = replaceLuhnGated(out, SIN, 9, 9, "[REDACTED-SIN]", count);
        out = replace(out, EMAIL, "[REDACTED-EMAIL]", count);
        out = replace(out, IPV4, "[REDACTED-IP]", count);
        return new Result(out, count[0]);
    }

    private static String replace(String in, Pattern p, String with, int[] count) {
        Matcher m = p.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement(with));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Redact only digit runs whose digit count is within [{@code minDigits}, {@code maxDigits}] AND pass Luhn —
     * so order ids, counts, version quads and ordinary long numbers are left intact (and the attestation count
     * isn't inflated). Used for both card numbers (13–19) and Canadian SINs (9, which are Luhn-valid).
     */
    private static String replaceLuhnGated(String in, Pattern p, int minDigits, int maxDigits, String with, int[] count) {
        Matcher m = p.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() >= minDigits && digits.length() <= maxDigits && luhn(digits)) {
                count[0]++;
                m.appendReplacement(sb, Matcher.quoteReplacement(with));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhn(String digits) {
        if (digits == null || digits.isEmpty()) {
            return false;   // an empty string trivially satisfies "sum % 10 == 0" — not a valid number
        }
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }
}
