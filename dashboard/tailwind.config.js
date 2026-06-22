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
        gold: { DEFAULT: v('--gold'), fill: v('--gold-fill'), tint: v('--gold-tint') },
        bg: v('--bg'),
        surface: v('--surface'),
        border: v('--border'),
        muted: v('--muted'),
        sidebar: v('--sidebar'),
        success: v('--success'),
        warning: v('--warning'),
        danger: v('--danger'),
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
      borderRadius: { md: '6px', lg: '10px', xl: '14px' },
      boxShadow: {
        card: '0 1px 2px rgb(14 23 38 / .06), 0 1px 3px rgb(14 23 38 / .10)',
        pop: '0 10px 34px rgb(14 23 38 / .16)',
      },
    },
  },
  plugins: [],
}
