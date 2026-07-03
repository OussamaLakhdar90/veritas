/**
 * The ONE verification gold — shared by the brand mark and the favicon (a Vitest guard asserts the favicon
 * still contains this hex). Kept hardcoded (like SnykLogo's palette) because it must render identically on any
 * surface, independent of the CSS-var theme.
 */
export const BRAND_GOLD = '#E3B85E';

/**
 * Veritas brand mark: a V whose right stroke resolves into a gold verification check — the product's promise
 * (checked, verified, safe) drawn as a letterform. The V stroke inherits the current text colour so it works
 * on the red sidebar tile, white surfaces and dark mode alike; the check is always verification gold (brand
 * constant, hardcoded like SnykLogo's palette). Sized by `className` like a lucide icon.
 */
export function VeritasLogo({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg"
      role="img" aria-label="Veritas">
      {/* left stroke of the V */}
      <path d="M6 6l10 20" stroke="currentColor" strokeWidth="4" strokeLinecap="round" />
      {/* right stroke resolving into the gold check */}
      <path d={`M16 26L27 6`} stroke={BRAND_GOLD} strokeWidth="4" strokeLinecap="round" />
      <path d={`M11.5 18.5L16 26`} stroke={BRAND_GOLD} strokeWidth="4" strokeLinecap="round" />
    </svg>
  );
}
