package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Closed-world id check + quote-grounding: a cited quote must actually appear in the cited unit's text. */
class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();
    private final ObjectMapper om = new ObjectMapper();

    private final Map<String, EvidenceUnit> byId = Map.of(
            "JIRA-1", EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Lockout",
                    "Account locks after 5 failed attempts", null, Set.of()),
            "CODE-1", EvidenceUnit.of("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "POST /login",
                    "no rate-limit annotation present", null, Set.of()));
    private final Set<String> allowed = Set.of("JIRA-1", "CODE-1");

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validWhenIdIsAllowedAndQuoteIsGroundedInTheUnitText() {
        CitationValidator.Result r = validator.validate(
                json("[{\"unitId\":\"JIRA-1\",\"quote\":\"locks after 5\"}]"), byId, allowed);
        assertThat(r.valid()).isTrue();
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void invalidWhenACitedIdIsOutsideTheAllowedSet() {
        CitationValidator.Result r = validator.validate(
                json("[{\"unitId\":\"GHOST-9\",\"quote\":\"x\"}]"), byId, allowed);
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("GHOST-9"));
    }

    @Test
    void invalidWhenTheQuoteIsNotInTheCitedUnit() {
        CitationValidator.Result r = validator.validate(
                json("[{\"unitId\":\"JIRA-1\",\"quote\":\"unlimited login attempts allowed\"}]"), byId, allowed);
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("quote"));
    }

    @Test
    void invalidWhenNoEvidenceIsCited() {
        assertThat(validator.validate(json("[]"), byId, allowed).valid()).isFalse();
        assertThat(validator.validate(null, byId, allowed).valid()).isFalse();
    }

    @Test
    void rejectsAnAllowedIdWithNoQuote() {
        // Closed-world id alone proves nothing about the claim it backs: every citation must carry a grounded quote,
        // else a section could cite a real id while fabricating the content (the bypass this check now closes).
        CitationValidator.Result r = validator.validate(json("[{\"unitId\":\"CODE-1\"}]"), byId, allowed);
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("no quote"));
    }

    @Test
    void invalidWhenTheQuoteIsTooShortToGroundAClaim() {
        // a 1-char quote substring-matches almost anything — reject it so a fabricated claim can't slip through.
        CitationValidator.Result r = validator.validate(json("[{\"unitId\":\"JIRA-1\",\"quote\":\"a\"}]"), byId, allowed);
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("too short"));
    }

    @Test
    void claimsAreNoOpWhenAbsent() {
        // backward compatible: no claims[] → nothing to bind, valid.
        assertThat(validator.validateClaims(json("[]"), json("[{\"unitId\":\"JIRA-1\"}]"), byId).valid()).isTrue();
        assertThat(validator.validateClaims(null, json("[{\"unitId\":\"JIRA-1\"}]"), byId).valid()).isTrue();
    }

    @Test
    void validWhenAClaimCitesACitedUnitAndSharesAGroundingTerm() {
        CitationValidator.Result r = validator.validateClaims(
                json("[{\"text\":\"The account locks after five failures\",\"citationRef\":\"JIRA-1\"}]"),
                json("[{\"unitId\":\"JIRA-1\",\"quote\":\"locks after 5\"}]"), byId);
        assertThat(r.valid()).isTrue();   // "locks" (>=4 chars) appears in the cited unit's text
    }

    @Test
    void invalidWhenAClaimCitesAnIdNotInTheEvidenceList() {
        CitationValidator.Result r = validator.validateClaims(
                json("[{\"text\":\"x\",\"citationRef\":\"CODE-1\"}]"),
                json("[{\"unitId\":\"JIRA-1\",\"quote\":\"locks after 5\"}]"), byId);   // CODE-1 not cited
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("CODE-1"));
    }

    @Test
    void invalidWhenAClaimSharesNoTermWithItsCitedEvidence() {
        // cites a real, cited unit but the claim text is unrelated to that unit's content → ungrounded claim.
        CitationValidator.Result r = validator.validateClaims(
                json("[{\"text\":\"unlimited retries permitted forever\",\"citationRef\":\"JIRA-1\"}]"),
                json("[{\"unitId\":\"JIRA-1\",\"quote\":\"locks after 5\"}]"), byId);
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("JIRA-1"));
    }

    @Test
    void invalidWhenAnAllowedIdHasNoResolvableUnit() {
        // 'GONE' is allowed but absent from unitsById → reject even with no quote (no silent ungrounded citation).
        CitationValidator.Result r = validator.validate(json("[{\"unitId\":\"GONE\"}]"), byId, Set.of("JIRA-1", "GONE"));
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("GONE"));
    }
}
