import { BookOpen } from 'lucide-react';
import { Badge, Card, CardBody, CardHeader, PageHeader } from '../components/ui';
import { SEVERITY_BADGE, LAYER_LABEL, CONFIDENCE_LABEL, TONE } from '../theme/tokens';

/** A single term row: the labelled chip on the left, a plain-language meaning on the right. */
function Term({ chip, children }: { chip: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5 py-3 sm:flex-row sm:items-start sm:gap-4">
      <div className="w-44 shrink-0">{chip}</div>
      <p className="text-[13.5px] leading-relaxed text-ink-900">{children}</p>
    </div>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <Card className="mb-5">
      <CardHeader title={title} subtitle={subtitle} />
      <CardBody className="divide-y divide-border/60 py-1">{children}</CardBody>
    </Card>
  );
}

/**
 * In-app glossary — the plain-language vocabulary Veritas uses on screen and in the management report.
 * It pulls its labels from the same token maps the rest of the UI uses, so it can never drift from them.
 */
export function Glossary() {
  return (
    <div className="max-w-3xl">
      <PageHeader
        title="Glossary"
        subtitle="What the words on the report and the findings list actually mean — in plain language."
      />

      <Card className="mb-5 border-l-4 border-l-brand">
        <CardBody className="flex items-start gap-3">
          <BookOpen className="mt-0.5 h-5 w-5 shrink-0 text-brand" />
          <p className="text-[13.5px] leading-relaxed text-ink-900">
            Veritas compares what a service's <strong>code actually does</strong> with what its <strong>API
            contract promises</strong>, and lists every difference as a <em>finding</em>. The terms below
            explain how those findings are labelled so you can judge them without reading the code.
          </p>
        </CardBody>
      </Card>

      <Section title="The bottom line"
        subtitle="The one-glance verdict at the top of every management report.">
        <Term chip={<Badge className={TONE.ok}>Proceed</Badge>}>
          The contract matches the code closely enough to release. No release-blocking differences were found.
        </Term>
        <Term chip={<Badge className={TONE.warn}>Hold for fixes</Badge>}>
          The service works, but the contract has gaps worth correcting before you publish it. Nothing here
          blocks a release on its own.
        </Term>
        <Term chip={<Badge className={TONE.danger}>Do not release</Badge>}>
          At least one release-blocking difference was found — something a consumer relies on is wrong or
          missing. Fix these before shipping.
        </Term>
      </Section>

      <Section title="Severity"
        subtitle="How much a single difference matters. Higher severity = more impact on the people calling the API.">
        <Term chip={<Badge className={SEVERITY_BADGE.BLOCKER}>Blocker</Badge>}>
          Will break callers in production. Must be fixed before release.
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.CRITICAL}>Critical</Badge>}>
          A serious mismatch that is very likely to cause failures or confusion. Fix before release.
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.MAJOR}>Major</Badge>}>
          A real gap worth correcting — the contract is misleading, but most callers will still work.
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.MINOR}>Minor</Badge>}>
          A small inaccuracy or polish item. Safe to defer.
        </Term>
        <Term chip={<Badge className={SEVERITY_BADGE.INFO}>Info</Badge>}>
          An observation, not a defect. Nothing to fix.
        </Term>
      </Section>

      <Section title="Confidence"
        subtitle="How sure Veritas is that a finding is real. Use it to decide what to double-check.">
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{CONFIDENCE_LABEL.HIGH}</span>}>
          Proven directly from the code or the contract. Safe to act on as-is.
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">{CONFIDENCE_LABEL.MEDIUM}</span>}>
          Well-supported, but worth a quick look before raising a defect.
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-warning">{CONFIDENCE_LABEL.LOW}</span>}>
          A reasonable suspicion that needs a human to confirm. A <strong>high-severity finding marked low
          confidence</strong> is flagged with a warning icon — verify it before you treat it as a blocker.
        </Term>
      </Section>

      <Section title="Analysis area"
        subtitle="Which part of the API a finding is about. Veritas checks six areas; you never need the internal codes.">
        {Object.entries(LAYER_LABEL).map(([code, label]) => (
          <Term key={code} chip={<span className="text-[13px] font-medium text-ink-900">{label}</span>}>
            {LAYER_BLURB[code]}
          </Term>
        ))}
      </Section>

      <Section title="Review status"
        subtitle="What you've decided about a finding. Your decision is recorded with your name and the time.">
        <Term chip={<Badge className={TONE.ok}>Accepted</Badge>}>
          A real difference that matches the code. It stays in the management report and can be raised as a defect.
        </Term>
        <Term chip={<Badge className={TONE.muted}>Rejected</Badge>}>
          Not applicable to this service. It's removed from the report and won't be raised as a defect. You can
          change this later.
        </Term>
        <Term chip={<Badge className={TONE.ok}>Defect raised</Badge>}>
          A Jira defect has been created for this finding and is now tracked there.
        </Term>
      </Section>

      <Section title="Report metrics"
        subtitle="The numbers on the management report.">
        <Term chip={<span className="text-[13px] font-medium text-ink-900">Contract fidelity</span>}>
          A 0–100 score for how faithfully the published contract describes the real service. Higher is better;
          the release gate passes at 90 or above.
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">Analysis coverage</span>}>
          How much of the service Veritas was able to examine. <strong>Full</strong> means every source it needed
          was available; <strong>Partial</strong> means something (a security source, an exception handler, a DTO)
          wasn't supplied, so some checks were skipped.
        </Term>
        <Term chip={<span className="text-[13px] font-medium text-ink-900">Est. analysis cost</span>}>
          The estimated cost of the AI calls used for this validation. Most checks are done without AI; it's used
          only where a deterministic check isn't enough.
        </Term>
      </Section>
    </div>
  );
}

/** One-line meaning for each analysis area, keyed by the same codes as LAYER_LABEL. */
const LAYER_BLURB: Record<string, string> = {
  L1: 'Is the contract itself well-formed and structurally valid?',
  L2: 'Does the contract list every endpoint the code actually exposes — and nothing it doesn\'t?',
  L3: 'Are the endpoints, parameters and responses documented clearly enough to use?',
  L4: 'Do the request and response shapes in the contract match what the code really sends and accepts?',
  L5: 'Does the API follow sound design conventions (naming, status codes, consistency)?',
  L6: 'Is there enough in the contract to derive a solid set of tests from it?',
};
