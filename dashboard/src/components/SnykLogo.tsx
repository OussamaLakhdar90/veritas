/**
 * Snyk brand mark (a stylized "Patch" hound head). Two modes: `mono` inherits the current text colour (used in
 * the dark sidebar), otherwise it renders in the Snyk purple. Sized by `className` like a lucide icon so it can
 * drop into the nav in place of one.
 */
export function SnykLogo({ className, mono }: { className?: string; mono?: boolean }) {
  const main = mono ? 'currentColor' : '#4B45AB';
  const ear = mono ? 'currentColor' : '#8A85D6';
  return (
    <svg className={className} viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg"
      role="img" aria-label="Snyk">
      {/* ears */}
      <path d="M6 3l6 2 1 7-6-2z" fill={ear} />
      <path d="M26 3l-6 2-1 7 6-2z" fill={ear} />
      {/* head + snout */}
      <path d="M12 5c1.4-1 2.7-1.4 4-1.4S18.6 4 20 5l1 7c1.6 2 1.7 4.6.2 7.2-1 1.7-2.4 3.2-3.9 4.4-.5.4-.9.8-1.3 1.4-.4-.6-.8-1-1.3-1.4-1.5-1.2-2.9-2.7-3.9-4.4-1.5-2.6-1.4-5.2.2-7.2z"
        fill={main} />
      {/* eyes (colour mode only — cut-outs read as background) */}
      {!mono && <>
        <circle cx="13" cy="12.5" r="1.3" fill="#fff" />
        <circle cx="19" cy="12.5" r="1.3" fill="#fff" />
      </>}
    </svg>
  );
}

/** Monochrome variant for the sidebar nav — inherits the link colour like the lucide icons around it. */
export function SnykNavIcon({ className }: { className?: string }) {
  return <SnykLogo className={className} mono />;
}
