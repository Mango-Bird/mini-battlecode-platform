import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { validateProductionEndpoint } from './deployment-config';
export default defineConfig(({ command, mode }) => {
  if (command === 'build') validateProductionEndpoint(loadEnv(mode, process.cwd(), 'VITE_').VITE_WS_URL);
  return { plugins: [react()] };
});
