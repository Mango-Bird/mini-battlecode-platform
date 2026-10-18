import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { validateProductionEndpoint } from './deployment-config';
export default defineConfig(({ command, mode }) => {
  if (command === 'build') {
    const env = loadEnv(mode, process.cwd(), 'VITE_');
    if (env.VITE_RUNTIME_MODE === 'SAFE_STAGING_WEB_ONLY') {
      if (env.VITE_WS_URL || env.VITE_BOT_API_URL) throw new Error('Safe staging uses same-origin BFF only; remove VITE_WS_URL/VITE_BOT_API_URL');
    } else {
      if (env.VITE_RUNTIME_MODE) throw new Error('Unknown frontend runtime mode');
      validateProductionEndpoint(env.VITE_WS_URL);
    }
  }
  return { plugins: [react()] };
});
