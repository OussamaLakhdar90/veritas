import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Guards nav ↔ route parity: every sidebar destination (a NAV_ITEMS `to` in lib/nav.ts) must have a matching
 * `<Route path="…">` in App.tsx. A nav entry pointing at a path no route renders is a dead link that shows a
 * blank "No routes matched" page — the exact bug that shipped the Activity item (and its imported page) with
 * no route wired into <Routes>. NAV_ITEMS only holds static paths (no params), so an exact set membership is
 * the right check; parameterized detail routes (…/:id) are intentionally not nav destinations.
 */
describe('nav ↔ routes', () => {
  const dir = path.dirname(fileURLToPath(import.meta.url));
  const app = fs.readFileSync(path.resolve(dir, '..', 'App.tsx'), 'utf8');
  const nav = fs.readFileSync(path.resolve(dir, '..', 'lib', 'nav.ts'), 'utf8');

  const routePaths = new Set([...app.matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]));
  const navTos = [...nav.matchAll(/\bto:\s*'([^']+)'/g)].map((m) => m[1]);

  it('has nav entries and routes to compare', () => {
    expect(navTos.length).toBeGreaterThan(5);
    expect(routePaths.size).toBeGreaterThan(5);
  });

  it('every sidebar destination has a <Route> in App.tsx', () => {
    const dead = navTos.filter((to) => !routePaths.has(to));
    expect(dead, `sidebar links with no matching <Route path="…">: ${dead.join(', ')}`).toEqual([]);
  });
});
