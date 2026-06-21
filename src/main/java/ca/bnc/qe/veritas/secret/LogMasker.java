package ca.bnc.qe.veritas.secret;

import java.util.Set;
import java.util.regex.Pattern;

/** Redacts secrets from log messages: known secret values + Authorization Bearer/Basic tokens. */
public final class LogMasker {

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+\\S+");
    private static final Pattern BASIC = Pattern.compile("(?i)(basic)\\s+\\S+");

    private LogMasker() {
    }

    public static String mask(String message, Set<String> secrets) {
        if (message == null) {
            return null;
        }
        String out = message;
        if (secrets != null) {
            for (String s : secrets) {
                if (s != null && !s.isBlank()) {
                    out = out.replace(s, "***");
                }
            }
        }
        out = BEARER.matcher(out).replaceAll("$1 ***");
        out = BASIC.matcher(out).replaceAll("$1 ***");
        return out;
    }
}
