package ca.bnc.qe.veritas.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Content-derived ids: stable across reordering/insertion, sensitive to real edits, unique per code element. */
class EvidenceIdTest {

    /**
     * The acceptance criterion from the design (§2): inserting a unit at the top of a source must NOT renumber
     * the others — every pre-existing unit keeps its id. The old positional {@code #slug-N} scheme failed this.
     */
    @Test
    void insertingAUnitAtTheTopDoesNotChangeAnyOtherUnitsId() {
        List<String[]> sections = new ArrayList<>(List.of(
                new String[]{"Auth flow", "User locks out after 5 failed attempts."},
                new String[]{"Token issuance", "JWT valid for 15 minutes."},
                new String[]{"Logout", "Session is invalidated server-side."}));
        List<String> before = idsOf("CONF-A", sections);

        // Re-ingest after a new section is inserted at the very top.
        sections.add(0, new String[]{"Overview", "This page describes the authentication subsystem."});
        List<String> after = idsOf("CONF-A", sections);

        // Every original id is still present (the three originals survived the insert).
        assertThat(after).containsAll(before);
        // And specifically the same heading+text maps to the same id before and after.
        assertThat(EvidenceId.section("CONF-A", "Auth flow", "User locks out after 5 failed attempts."))
                .isEqualTo(before.get(0));
    }

    private static List<String> idsOf(String page, List<String[]> sections) {
        List<String> ids = new ArrayList<>();
        for (String[] s : sections) {
            ids.add(EvidenceId.section(page, s[0], s[1]));
        }
        return ids;
    }

    @Test
    void sameContentIsDeterministicAcrossCalls() {
        assertThat(EvidenceId.section("CONF-A", "Auth flow", "Locks after 5 tries."))
                .isEqualTo(EvidenceId.section("CONF-A", "Auth flow", "Locks after 5 tries."));
    }

    @Test
    void normalisationIgnoresWhitespaceAndCaseButNotRealEdits() {
        String a = EvidenceId.section("CONF-A", "Auth flow", "Locks after 5 tries.");
        String reformatted = EvidenceId.section("CONF-A", "Auth flow", "  locks   after 5   TRIES.  ");
        String edited = EvidenceId.section("CONF-A", "Auth flow", "Locks after 3 tries.");
        assertThat(reformatted).isEqualTo(a);   // trivial reformatting → same id
        assertThat(edited).isNotEqualTo(a);     // a real content change → re-mints
    }

    @Test
    void codeEndpointIdDisambiguatesByDeclaringClassAndMethod() {
        // Two controllers with a login() collide on bare method name — the class + HTTP + path id does not.
        assertThat(EvidenceId.endpoint("AuthController", "post", "/login"))
                .isEqualTo("CODE:AuthController#POST /login")
                .isNotEqualTo(EvidenceId.endpoint("AdminController", "post", "/login"));
        assertThat(EvidenceId.endpoint("AuthController", "get", "/login"))
                .isNotEqualTo(EvidenceId.endpoint("AuthController", "post", "/login"));
    }

    @Test
    void policyAndDtoIdsAreSlugged() {
        assertThat(EvidenceId.policy("OWASP API", "Rate Limiting")).isEqualTo("POLICY:owasp-api-rate-limiting");
        assertThat(EvidenceId.dtoConstraint("PasswordPolicy", "minLength")).isEqualTo("CODE:PasswordPolicy.minLength");
    }

    @Test
    void hash8IsEightLowercaseBase32Chars() {
        String h = EvidenceId.hash8("some text");
        assertThat(h).hasSize(8).matches("[a-z2-7]{8}");
    }

    @Test
    void slugFoldsDiacriticsSoFrenchHeadingsStayReadable() {
        assertThat(EvidenceId.slug("Café Liégeois")).isEqualTo("cafe-liegeois");
        assertThat(EvidenceId.slug("Gestion des accès")).isEqualTo("gestion-des-acces");
        assertThat(EvidenceId.slug("   ")).isEqualTo("x");   // empty/blank → safe placeholder
    }
}
