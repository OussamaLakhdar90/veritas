package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Structural no-leak guard for the third surface in the security plan (§10.9): secrets must never be
 * persisted. Rather than store a token and grep the DB (flaky), this asserts the JPA model itself can't
 * hold one — every mapped attribute's name is checked against a secret pattern. @Transient fields are
 * absent from the metamodel, so a field is flagged only if it actually maps to a column. A regression that
 * adds e.g. a {@code gitToken} column fails the build here, by construction.
 */
@SpringBootTest
class NoSecretInPersistedRowsTest {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|password|passphrase|secret|api[-_]?key|credential|client[-_]?secret)");

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void noEntityAttributeStoresASecret() {
        List<String> offenders = new ArrayList<>();
        for (ManagedType<?> type : emf.getMetamodel().getManagedTypes()) {
            for (Attribute<?, ?> attr : type.getAttributes()) {
                // A secret is always text. Numeric columns like estTokensIn/Out are LLM token COUNTS, not credentials.
                if (CharSequence.class.isAssignableFrom(attr.getJavaType()) && SECRET.matcher(attr.getName()).find()) {
                    offenders.add(type.getJavaType().getSimpleName() + "." + attr.getName());
                }
            }
        }
        assertThat(offenders)
                .as("persisted columns must never hold secrets — resolve them at use-time from SecretProvider instead")
                .isEmpty();
    }
}
