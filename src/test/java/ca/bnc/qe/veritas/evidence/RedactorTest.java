package ca.bnc.qe.veritas.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Deterministic PII/secret redaction, non-silent (counts), and conservative (Luhn-gated card numbers). */
class RedactorTest {

    @Test
    void redactsCardNumberThatPassesLuhn() {
        // 4111 1111 1111 1111 is a Luhn-valid test PAN.
        Redactor.Result r = Redactor.redact("card on file 4111 1111 1111 1111 thanks");
        assertThat(r.text()).contains("[REDACTED-PAN]").doesNotContain("4111");
        assertThat(r.count()).isEqualTo(1);
    }

    @Test
    void leavesLongNonCardNumberAlone() {
        // 1234567812345678 is a standalone 16-digit run that FAILS Luhn → not a card, left intact
        // (so order ids / reference numbers aren't falsely redacted).
        Redactor.Result r = Redactor.redact("order reference 1234567812345678 only");
        assertThat(r.text()).contains("1234567812345678");
        assertThat(r.count()).isZero();
    }

    @Test
    void redactsEmailJwtBearerSecretAndIp() {
        assertThat(Redactor.redact("ping alice@bnc.ca").text()).contains("[REDACTED-EMAIL]").doesNotContain("alice@bnc.ca");
        assertThat(Redactor.redact("Authorization: Bearer abc123XYZ._-tokenvalue").text()).contains("[REDACTED-AUTH]");
        assertThat(Redactor.redact("token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEF123").text())
                .contains("[REDACTED-TOKEN]");
        assertThat(Redactor.redact("password: hunter2plain").text()).contains("[REDACTED-SECRET]").doesNotContain("hunter2plain");
        assertThat(Redactor.redact("host 192.168.1.42 reachable").text()).contains("[REDACTED-IP]").doesNotContain("192.168.1.42");
    }

    @Test
    void cleanTextIsUntouchedAndCountsZero() {
        String clean = "The login endpoint locks the account after 5 failed attempts.";
        Redactor.Result r = Redactor.redact(clean);
        assertThat(r.text()).isEqualTo(clean);
        assertThat(r.count()).isZero();
    }

    @Test
    void countsEveryRedactedSpan() {
        Redactor.Result r = Redactor.redact("mail a@b.co and c@d.co from 10.0.0.1");
        assertThat(r.count()).isEqualTo(3);   // two emails + one IP
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(Redactor.redact(null).count()).isZero();
        assertThat(Redactor.redact("   ").count()).isZero();
    }

    @Test
    void luhnCheckIsCorrect() {
        assertThat(Redactor.luhn("4111111111111111")).isTrue();
        assertThat(Redactor.luhn("4111111111111112")).isFalse();
        assertThat(Redactor.luhn("")).isFalse();   // empty must not vacuously pass (0 % 10 == 0)
    }

    @Test
    void redactsCanadianSinThatPassesLuhnButNotAPlainNineDigitRun() {
        // 046 454 286 is a Luhn-valid sample SIN.
        assertThat(Redactor.redact("SIN 046 454 286 on file").text())
                .contains("[REDACTED-SIN]").doesNotContain("046 454 286");
        // 123 456 789 fails Luhn → left intact (not every 9-digit number is a SIN).
        assertThat(Redactor.redact("ticket 123 456 789").text()).contains("123 456 789");
    }

    @Test
    void redactsBareCloudAndVcsTokens() {
        assertThat(Redactor.redact("key AKIAIOSFODNN7EXAMPLE used").text()).contains("[REDACTED-SECRET]").doesNotContain("AKIA");
        assertThat(Redactor.redact("token ghp_abcdefghijklmnopqrstuvwxyz0123456789 here").text())
                .contains("[REDACTED-SECRET]").doesNotContain("ghp_");
    }

    @Test
    void emailRegexIsLinearOnLongFailingInput() {
        // The fixed EMAIL pattern must not backtrack catastrophically on a long, never-terminating @-run.
        String hostile = "x@" + "a-".repeat(40000);   // ~80k chars, no valid TLD
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Redactor.Result r = Redactor.redact(hostile);
            assertThat(r.count()).isZero();   // not an email → nothing redacted
        });
        // ...and a real email still matches.
        assertThat(Redactor.redact("reach me at a.b@sub.example.co please").text()).contains("[REDACTED-EMAIL]");
    }
}
