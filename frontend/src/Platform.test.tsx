// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Platform from './Platform';
import { readDraft, keepDraft } from './drafts';
import { setNavigationBlocked } from './navigation';
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body });
beforeEach(() => { history.replaceState(null, '', '/'); vi.stubGlobal('scrollTo', vi.fn()); });
afterEach(() => { cleanup(); setNavigationBlocked(false); keepDraft('A', null); keepDraft('B', null); localStorage.clear(); vi.unstubAllGlobals(); });
const user = { id: 'A', username: 'alice' };
it('shared user menu logs out and immediately removes private account content', async () => {
    const fetcher = vi.fn(async (url: string) => response(url.endsWith('/auth/me') ? { user } : { loggedOut: true }));
    vi.stubGlobal('fetch', fetcher); history.replaceState(null, '', '/account'); render(<Platform />);
    await screen.findByText('User ID: A'); fireEvent.click(within(screen.getByRole('main')).getByRole('button', { name: 'Logout' }));
    await screen.findByRole('heading', { name: 'Sign in to continue' }); expect(screen.queryByText('User ID: A')).toBeNull();
    expect(fetcher.mock.calls.some(call => call[0].endsWith('/auth/logout'))).toBe(true);
});
function api(url: string) { return response(url.endsWith('/auth/me') ? { user } : url.endsWith('/tournaments') ? { tournaments: [], viewerOwnerId: 'A' } : url.endsWith('/match-history') ? { matches: [] } : { submissions: [] }); }
it('navigates public routes without reload and keeps protected source hidden', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 401)));
    render(<Platform />);
    await screen.findByRole('heading', { name: /Write the bot/ });
    fireEvent.click(screen.getByRole('link', { name: 'Explore the games' }));
    expect(location.pathname).toBe('/games');
    await screen.findByRole('heading', { name: 'Choose your battleground' });
    fireEvent.click(screen.getByRole('link', { name: 'Enter arena →' }));
    await screen.findByRole('heading', { name: 'Sign in to continue' });
    expect(screen.queryByRole('button', { name: 'Start Battle' })).toBeNull();
});
it('dashboard derives counts from real response bodies and sends credentialed requests', async () => {
    const fetcher = vi.fn(async (url: string) => api(url));
    vi.stubGlobal('fetch', fetcher);
    history.replaceState(null, '', '/dashboard');
    render(<Platform />);
    await screen.findByRole('heading', { name: 'Welcome back, alice' });
    await screen.findByRole('heading', { name: 'Your first strategy starts here' });
    expect(screen.getByText('No recorded matches')).toBeTruthy();
    expect(screen.queryByText(/win rate:|rank #/i)).toBeNull();
    expect(fetcher.mock.calls.every(call => (call as unknown as [
        string,
        RequestInit
    ])[1].credentials === 'include')).toBe(true);
    const nav = screen.getByRole('navigation', { name: 'Player navigation' });
    fireEvent.click(within(nav).getByRole('link', { name: /My Bots/ }));
    await screen.findByRole('heading', { name: 'My Bots', level: 1 });
});
it('protects private pages on expiry and cross-tab account changes', async () => {
    let identity = user;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/auth/me') ? { user: identity } : { submissions: [] })));
    history.replaceState(null, '', '/account');
    render(<Platform />);
    await screen.findByText('User ID: A');
    identity = { id: 'B', username: 'bob' };
    fireEvent(window, new StorageEvent('storage', { key: 'battlecode-auth-change' }));
    await screen.findByText('User ID: B');
    expect(screen.queryByText('User ID: A')).toBeNull();
    fireEvent(window, new Event('battlecode-session-expired'));
    await screen.findByRole('heading', { name: 'Sign in to continue' });
    expect(screen.queryByText('User ID: B')).toBeNull();
});
it('warns on SPA navigation, retains draft on expiry and restores only the same account', async () => {
    let identity = user;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/auth/me') ? { user: identity } : { submissions: [] })));
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));
    history.replaceState(null, '', '/bot-workspace');
    render(<Platform />);
    const editor = await screen.findByLabelText('JavaScript source');
    fireEvent.change(editor, { target: { value: 'private draft A' } });
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Player navigation' })).getByRole('link', { name: /My Bots/ }));
    expect(location.pathname).toBe('/bot-workspace');
    expect(window.confirm).toHaveBeenCalled();
    fireEvent(window, new Event('battlecode-session-expired'));
    await screen.findByRole('heading', { name: 'Sign in to continue' });
    expect(readDraft('A')?.source).toBe('private draft A');
    expect(screen.queryByDisplayValue('private draft A')).toBeNull();
    identity = { id: 'B', username: 'bob' };
    fireEvent(window, new StorageEvent('storage', { key: 'battlecode-auth-change' }));
    await screen.findByLabelText('JavaScript source');
    expect(screen.queryByDisplayValue('private draft A')).toBeNull();
    identity = user;
    fireEvent(window, new StorageEvent('storage', { key: 'battlecode-auth-change' }));
    await screen.findByDisplayValue('private draft A');
    expect(localStorage.getItem('source')).toBeNull();
});
it('supports query deep links for an owned bot and preserves cookie API contract', async () => {
    const metadata = { botId: 'owned', name: 'Strategy', latestVersion: 1, sourceSha256: 'abc', validationStatus: 'SOURCE_ACCEPTED', executionEligibility: 'NOT_EXECUTABLE', createdAt: '', updatedAt: '' };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/auth/me') ? { user } : url.endsWith('/submissions/owned') ? { metadata, versions: [{ version: 1, source: 'private saved source', sourceSha256: 'abc', createdAt: '', validationStatus: 'SOURCE_ACCEPTED', executionEligibility: 'NOT_EXECUTABLE' }] } : { submissions: [metadata] })));
    history.replaceState(null, '', '/bot-workspace?bot=owned');
    render(<Platform />);
    await screen.findByDisplayValue('private saved source');
    expect(screen.getByText('SHA-256: abc')).toBeTruthy();
});
it('shows partial API errors without converting missing data to zero', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { if (url.endsWith('/match-history'))
        throw new Error('History unavailable'); return api(url); }));
    history.replaceState(null, '', '/dashboard');
    render(<Platform />);
    await screen.findByText('History unavailable');
    expect(screen.getByText('Unavailable')).toBeTruthy();
});
it('renders unknown routes honestly', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 401))); history.replaceState(null, '', '/unknown'); render(<Platform />); await screen.findByText('Page not found'); });
it('login uses SPA refresh and never stores a session token', async () => {
    let loggedIn = false;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { if (url.endsWith('/auth/login')) {
        loggedIn = true;
        return response({ user });
    } if (url.endsWith('/auth/me') && !loggedIn)
        return response({}, 401); return api(url); }));
    history.replaceState(null, '', '/login');
    render(<Platform />);
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-test-password-only' } });
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));
    await screen.findByRole('heading', { name: 'Welcome back, alice' });
    await waitFor(() => expect(location.pathname).toBe('/dashboard'));
    expect(localStorage.length).toBe(1);
    expect(localStorage.key(0)).toBe('battlecode-auth-change');
});
