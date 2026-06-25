package ca.bnc.qe.veritas.integration.jira;

import java.util.regex.Pattern;

/**
 * Builds the JQL that selects an epic's child issues — deterministic, no LLM. The clause differs by Jira edition:
 * <ul>
 *   <li><b>Cloud / team-managed</b>: {@code parent = "EPIC-1"} (the next-gen parent field);</li>
 *   <li><b>Server/DC (classic)</b>: the "Epic Link" custom field — {@code cf[10001] = "EPIC-1"} when its numeric
 *       id was discovered via create-meta, else the field name {@code "Epic Link" = "EPIC-1"}.</li>
 * </ul>
 *
 * <p>The epic key is strictly validated against the Jira key grammar and quoted, so an attacker-supplied value can
 * never break out of the clause or inject JQL.
 */
public final class EpicChildrenJql {

    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private EpicChildrenJql() {}

    /** @param edition the Jira edition ({@code CLOUD} / {@code SERVER_DC}); @param epicLinkFieldKey e.g. {@code customfield_10001} or null. */
    public static String forEpic(String edition, String epicKey, String epicLinkFieldKey) {
        if (epicKey == null || !KEY.matcher(epicKey.trim()).matches()) {
            throw new IllegalArgumentException("Invalid Jira epic key '" + epicKey + "' — expected e.g. CIAM-100.");
        }
        String key = epicKey.trim();
        if ("SERVER_DC".equalsIgnoreCase(edition)) {
            String field = numericFieldId(epicLinkFieldKey)
                    .map(id -> "cf[" + id + "]")
                    .orElse("\"Epic Link\"");
            return field + " = \"" + key + "\"";
        }
        return "parent = \"" + key + "\"";   // Cloud / team-managed
    }

    private static java.util.Optional<String> numericFieldId(String epicLinkFieldKey) {
        if (epicLinkFieldKey == null || epicLinkFieldKey.isBlank()) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher m = DIGITS.matcher(epicLinkFieldKey);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }
}
