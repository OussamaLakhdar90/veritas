package ca.bnc.qe.veritas.evidence;

/**
 * Where a piece of evidence comes from. The first three are <b>evidence</b> sources (cited to ground claims);
 * {@link #POLICY} is a non-project, pre-authored source that makes mandatory standards (security, performance,
 * compliance) citable under the closed-world rule. See the multi-source test-strategy design, §1.
 */
public enum SourceKind {
    JIRA,
    CONFLUENCE,
    CODE,
    POLICY
}
