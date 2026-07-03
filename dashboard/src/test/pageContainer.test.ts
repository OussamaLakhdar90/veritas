import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Guards page-root width: a page must centre + cap its content through the <PageContainer> primitive, never a
 * hand-rolled `max-w-*` on its outermost element. A bare `max-w-3xl` root (no `mx-auto`) left-hugs the viewport
 * on a wide monitor — the exact bug PageContainer fixes. Inner `max-w-*` (filter inputs, form fields) is fine;
 * this only inspects the element returned immediately after a component's `return (`.
 */
describe('page layout', () => {
  it('no page component roots its layout on a hand-rolled max-w-* (use PageContainer)', () => {
    const pagesDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'pages');
    const offenders: string[] = [];
    // Match `return (` → whitespace → an opening `<div ... className="…">` and inspect that class list.
    const rootDiv = /return\s*\(\s*<div\b[^>]*\bclassName="([^"]*)"/g;
    for (const entry of fs.readdirSync(pagesDir)) {
      if (!/\.tsx$/.test(entry)) continue;
      const content = fs.readFileSync(path.join(pagesDir, entry), 'utf8');
      let m: RegExpExecArray | null;
      while ((m = rootDiv.exec(content)) !== null) {
        if (/\bmax-w-/.test(m[1])) offenders.push(`${entry}: <div className="${m[1]}">`);
      }
    }
    expect(offenders, `page roots must use <PageContainer>, not a raw max-w-* div:\n${offenders.join('\n')}`)
      .toEqual([]);
  });

  it('the app shell caps the content column so pages never render full-bleed on a wide monitor', () => {
    // The global cap in App.tsx <main> is what keeps the 13 dense pages (incl. the flagship Dashboard) from
    // stretching edge-to-edge on a VP's wide display. This guard stops that cap being silently removed.
    const app = fs.readFileSync(
      path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'App.tsx'), 'utf8');
    expect(app, 'App.tsx must cap the routed content column (mx-auto max-w-[1600px])')
      .toMatch(/mx-auto max-w-\[1600px\]/);
  });
});
