import { defineConfig } from 'vite';
import path from 'path';

export default defineConfig({
  root: '.',
  build: {
    outDir: 'www',
    emptyOutDir: false,
    rollupOptions: {
      input: path.resolve(__dirname, 'src/app.ts'),
      output: {
        entryFileNames: 'app.js',
        format: 'iife',
        name: 'YandexAdsDemo'
      }
    }
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@capacitor/core': path.resolve(__dirname, 'node_modules/@capacitor/core')
    },
    dedupe: ['@capacitor/core']
  },
  server: {
    port: 3000
  }
});
