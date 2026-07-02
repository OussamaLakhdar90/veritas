import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Guards the whole-pixel type scale: arbitrary Tailwind font sizes (text-[13px], text-[12.5px]…) are banned —
 * they fragmented the app into 227 slightly-different sizes and rendered blurry at 125–150% Windows scaling.
 * Every size must come from the named scale in tailwind.config (2xs/xs/sm/md/lg/xl/title/display…).
 */
describe('type scale', () => {
  it('no component uses an arbitrary pixel font size', () => {
    const srcDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (/\.tsx?$/.test(entry.name) && entry.name !== 'typeScale.test.ts') {   // this file names the banned pattern
          const content = fs.readFileSync(full, 'utf8');
          const matches = content.match(/text-\[[0-9.]+px\]/g);
          if (matches) {
            offenders.push(`${path.relative(srcDir, full)}: ${[...new Set(matches)].join(', ')}`);
          }
        }
      }
    };
    walk(srcDir);
    expect(offenders, `arbitrary font sizes found — use the named scale instead:\n${offenders.join('\n')}`)
      .toEqual([]);
  });
});
