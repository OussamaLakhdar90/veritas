import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Guards the whole-pixel type scale: arbitrary Tailwind font sizes (text-[13px], text-[12.5px]…) are banned —
 * they fragmented the app into 227 slightly-different sizes and rendered blurry at 125–150% Windows scaling.
 * Every size must come from the named scale in tailwind.config (2xs/xs/sm/md/lg/xl/title/display…).
 */
const SRC_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Read every source .tsx/.ts under src/ (skipping this file, which names the banned patterns literally). */
function eachSourceFile(visit: (rel: string, content: string) => void) {
  const walk = (dir: string) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (/\.tsx?$/.test(entry.name) && entry.name !== 'typeScale.test.ts') {
        visit(path.relative(SRC_DIR, full), fs.readFileSync(full, 'utf8'));
      }
    }
  };
  walk(SRC_DIR);
}

describe('type scale', () => {
  it('no component uses an arbitrary pixel font size', () => {
    const offenders: string[] = [];
    eachSourceFile((rel, content) => {
      const matches = content.match(/text-\[[0-9.]+px\]/g);
      if (matches) offenders.push(`${rel}: ${[...new Set(matches)].join(', ')}`);
    });
    expect(offenders, `arbitrary font sizes found — use the named scale instead:\n${offenders.join('\n')}`)
      .toEqual([]);
  });

  // Tailwind's built-in text-base/2xl/3xl/4xl/5xl+ are OUTSIDE the whole-pixel named scale (they render at
  // fractional px under Windows 125–150% scaling and re-fragment the type system) — use xl/title/display/stat.
  it('no component uses a Tailwind-default text size outside the named scale', () => {
    const offenders: string[] = [];
    eachSourceFile((rel, content) => {
      const matches = content.match(/(?<![\w-])text-(?:base|[2-9]xl)(?![\w-])/g);
      if (matches) offenders.push(`${rel}: ${[...new Set(matches)].join(', ')}`);
    });
    expect(offenders, `default Tailwind text sizes found — use xl/title/display/stat instead:\n${offenders.join('\n')}`)
      .toEqual([]);
  });

  // Arbitrary two-plus-digit icon dimensions (h-[18px]/w-[24px]…) drift off the spacing scale. The lookbehind
  // spares min-w-/max-w-/min-h-/max-h- (layout constraints, legitimately arbitrary) — only bare h-/w- are banned.
  it('no component uses an arbitrary multi-digit icon dimension', () => {
    const offenders: string[] = [];
    eachSourceFile((rel, content) => {
      const matches = content.match(/(?<![\w-])[hw]-\[\d{2,}[0-9.]*px\]/g);
      if (matches) offenders.push(`${rel}: ${[...new Set(matches)].join(', ')}`);
    });
    expect(offenders, `arbitrary icon dimensions found — use a spacing token (e.g. h-4.5 = 18px):\n${offenders.join('\n')}`)
      .toEqual([]);
  });
});
