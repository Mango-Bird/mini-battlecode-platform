import { it, expect } from 'vitest';
import { validateProductionEndpoint } from '../deployment-config';
it('rejects missing, insecure and loopback production endpoints', () => {
  for (const endpoint of [undefined, 'ws://backend.example/ws', 'wss://localhost/ws', 'wss://127.0.0.1/ws', 'wss://[::1]/ws', 'wss://dev.localhost/ws', 'wss://user:secret@backend.example/ws'])
    expect(() => validateProductionEndpoint(endpoint)).toThrow();
});
it('accepts an explicit public WSS endpoint', () => {
  expect(() => validateProductionEndpoint('wss://backend.example/ws')).not.toThrow();
});
