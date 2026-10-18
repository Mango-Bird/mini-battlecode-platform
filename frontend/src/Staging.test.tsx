// @vitest-environment jsdom
// @vitest-environment-options {"url":"https://stage.vercel.app"}
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
vi.mock('./runtime', () => ({ safeStaging: true, playerToolsEnabled: true, executionUnavailable: 'Execution disabled on staging' }));
import Platform from './Platform';
import { localApi } from './local-submissions';
afterEach(() => { cleanup();vi.unstubAllGlobals(); });
describe('safe staging', () => {
 it('blocks execution pages without issuing any execution requests', async () => {
  const fetcher=vi.fn(async (_url: string) => ({ ok:true,status:200,json:async()=>({user:{id:'one',username:'alice'}}) }));vi.stubGlobal('fetch',fetcher);
  history.replaceState(null,'','/bot-arena');render(<Platform />);
  await screen.findByText('Execution disabled on staging');
  expect(screen.queryByRole('button',{name:/Start Battle/})).toBeNull();
  expect(fetcher.mock.calls.every(c=>String(c[0]).endsWith('/api/staging/auth/me'))).toBe(true);
 });
 it('uses same-origin HTTPS BFF and bootstraps CSRF for source writes',async()=>{
  vi.stubGlobal('window',{location:{origin:'https://stage.vercel.app'},dispatchEvent:vi.fn()});
  const fetcher=vi.fn().mockResolvedValueOnce({ok:true,json:async()=>({csrfToken:'signed-token'})})
   .mockResolvedValueOnce({ok:true,status:201,json:async()=>({saved:true})});vi.stubGlobal('fetch',fetcher);
  expect(await localApi('submissions','POST',{name:'Bot'})).toEqual({saved:true});
  expect(fetcher.mock.calls[0][0]).toBe('https://stage.vercel.app/api/staging/auth/csrf');
  expect(fetcher.mock.calls[1][1].headers['X-CSRF-Token']).toBe('signed-token');
  expect(fetcher.mock.calls[1][1].credentials).toBe('include');
 });
});
