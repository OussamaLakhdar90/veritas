import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BRAND_GOLD } from '../components/VeritasLogo';

/**
 * One verification gold. The favicon is a separate hand-authored SVG (it can't import from the bundle), so it
 * drifts silently from the brand mark unless something asserts they agree — this guard fails the build if the
 * favicon stops containing the BRAND_GOLD hex.
 */
describe('brand', () => {
  it('BRAND_GOLD is the single verification gold', () => {
    expect(BRAND_GOLD.toUpperCase()).toBe('#E3B85E');
  });

  it('the favicon uses BRAND_GOLD (matches VeritasLogo)', () => {
    const faviconPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../public/favicon.svg');
    const svg = fs.readFileSync(faviconPath, 'utf8');
    expect(svg.toUpperCase()).toContain(BRAND_GOLD.toUpperCase());
    // The V is white on the navy tile — not the in-app red — so it reads at 16px.
    expect(svg.toUpperCase()).toContain('#FFFFFF');
  });
});
