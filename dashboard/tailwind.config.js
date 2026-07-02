/** @type {import('tailwindcss').Config} */
const v = (name) => `rgb(var(${name}) / <alpha-value>)`;   // channel-triplet vars → opacity modifiers work

export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: { DEFAULT: v('--brand-600'), 50: v('--brand-50'), 600: v('--brand-600'), 700: v('--brand-700') },
        ink: { DEFAULT: v('--text'), 50: v('--ink-50'), 100: v('--ink-100'), 600: v('--ink-600'), 700: v('--ink-700'), 900: v('--ink-900') },
        gold: { DEFAULT: v('--gold'), tint: v('--gold-tint') },
        bg: v('--bg'),
        surface: v('--surface'),
        border: v('--border'),
        muted: v('--muted'),
        sidebar: v('--sidebar'),
        success: v('--success'),
        warning: v('--warning'),
        danger: { DEFAULT: v('--danger'), strong: v('--danger-strong') },
        info: v('--info'),
        sev: {
          blocker: v('--sev-blocker'), critical: v('--sev-critical'),
          major: v('--sev-major'), minor: v('--sev-minor'), info: v('--sev-info'),
        },
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      /* One whole-pixel type scale (line-height baked) — arbitrary text-[..px] values are banned; a Vitest
       * guard (typeScale.test.ts) fails the build if one creeps back in. `sm` is deliberately overridden to
       * 13px: the app's dominant body size, previously written 108× as text-[13px]. */
      fontSize: {
        '2xs': ['11px', '16px'],
        xs: ['12px', '18px'],
        sm: ['13px', '20px'],
        md: ['15px', '22px'],
        lg: ['18px', '26px'],
        title: ['24px', '30px'],
        display: ['28px', '34px'],
      },
      borderRadius: { md: '6px', lg: '10px', xl: '14px' },
      /* Elevation reads from CSS vars so dark mode can deepen the alpha (a single light-tuned shadow is
       * invisible on a navy surface). card = resting, lift = interactive hover, pop = overlays only. */
      boxShadow: {
        card: 'var(--shadow-card)',
        lift: 'var(--shadow-lift)',
        pop: 'var(--shadow-pop)',
      },
      transitionDuration: { fast: '150ms', base: '240ms' },
      /* Named `calm`, NOT `ease-out` — that would silently shadow Tailwind's built-in ease-out. */
      transitionTimingFunction: { calm: 'cubic-bezier(0.2, 0.7, 0.3, 1)' },
    },
  },
  plugins: [],
}
