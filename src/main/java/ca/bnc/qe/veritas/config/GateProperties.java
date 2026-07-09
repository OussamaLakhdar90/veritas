package ca.bnc.qe.veritas.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The release quality-gate thresholds — a categorical, per-severity gate (SonarQube-style), NOT a composite score.
 * A scan FAILs when its <b>counted</b> findings exceed any of these caps; it WARNs when only non-breaking drift
 * remains; otherwise it PASSes. Defaults are a zero-tolerance floor for anything that breaks a running consumer:
 * <ul>
 *   <li>{@code maxBlocker} / {@code maxCritical} — an unparseable spec or a definite endpoint-level / security
 *       (OWASP API1/2/5) break hard-fails at the first occurrence;</li>
 *   <li>{@code maxBreaking} — any consumer-breaking change (semver-major; the {@code DiffEngine.isBreaking} set)
 *       hard-fails. Additive / documentation-only drift (a field the code has that the spec lacks, an undocumented
 *       status) is non-breaking and never gates.</li>
 * </ul>
 * Tunable per team via {@code veritas.gate.*} so the gate is an explicit, auditable policy rather than a magic number.
 */
@Component
@ConfigurationProperties(prefix = "veritas.gate")
@Getter
@Setter
public class GateProperties {

    /** Max counted BLOCKER findings before FAIL (an unparseable/unresolvable spec). Default 0 = zero tolerance. */
    private int maxBlocker = 0;

    /** Max counted CRITICAL findings before FAIL (endpoint-level or security-contract break). Default 0. */
    private int maxCritical = 0;

    /** Max counted consumer-breaking findings (any severity, {@code isBreaking} type) before FAIL. Default 0. */
    private int maxBreaking = 0;
}
