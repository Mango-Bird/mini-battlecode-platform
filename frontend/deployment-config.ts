/** A production bundle must not silently ship the developer's .env.local endpoint. */
export function validateProductionEndpoint(value: string | undefined): void {
  if (!value) throw new Error('Production build requires VITE_WS_URL=wss://<backend-domain>/ws');
  const url = new URL(value);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== 'wss:' || url.username || url.password || host === 'localhost' || host.endsWith('.localhost')
    || host === '[::1]' || host === '0.0.0.0' || /^127\./.test(host))
    throw new Error('Production VITE_WS_URL must use WSS and a non-loopback backend hostname');
}
