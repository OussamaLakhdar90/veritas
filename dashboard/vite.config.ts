import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Builds into the Spring Boot static dir so the single jar serves the SPA.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
