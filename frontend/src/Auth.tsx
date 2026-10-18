import { playerToolsEnabled, safeStaging } from './runtime';
import { useEffect, useState, type ReactNode } from 'react';
import { localApi, notifyAuthChange, SessionExpiredError } from './local-submissions';
import './auth.css';
import { Link, useNavigation } from './navigation';
import { useSession } from './session';
import { PageHeader, Button, Input } from './ui';
export type User = { id: string; username: string };
export function AuthGate({ children, account = false }: { children?: ReactNode; account?: boolean }) {
  const [user, setUser] = useState<User | null>(null), [loading, setLoading] = useState(true), [error, setError] = useState('');
  useEffect(() => {
    let abort = new AbortController();
    const expired = () => { abort.abort(); setUser(null); setLoading(false); setError('Phiên đã hết hạn. Vui lòng đăng nhập lại.'); };
    const refresh = () => {
      abort.abort(); abort = new AbortController(); const current = abort;
      setUser(null); setLoading(true); setError('');
      void localApi<{ user: User }>('auth/me', 'GET', undefined, current.signal).then(data => { if (!current.signal.aborted) setUser(data.user); })
        .catch(e => { if (!current.signal.aborted) setError(e instanceof SessionExpiredError ? 'Đăng nhập để quản lý bot và tournaments.' : e.message); })
        .finally(() => { if (!current.signal.aborted) setLoading(false); });
    };
    const changed = (event: StorageEvent) => { if (event.key === 'battlecode-auth-change') refresh(); };
    window.addEventListener('battlecode-session-expired', expired);
    window.addEventListener('storage', changed);refresh();
    return () => { abort.abort(); window.removeEventListener('battlecode-session-expired', expired); window.removeEventListener('storage', changed); };
  }, []);
  async function logout() {
    try { await localApi('auth/logout', 'POST', {}); setUser(null); setError('Đã đăng xuất.'); notifyAuthChange(); }
    catch (e) { setError((e as Error).message); }
  }
  if (!playerToolsEnabled) return <p>Account workspace chỉ bật trong local development.</p>;
  if (loading) return <p role="status">Đang kiểm tra phiên đăng nhập…</p>;
  if (!user) return <main className="auth"><p role="alert">{error}</p><a href="/login">Login</a> · <a href="/register">Register</a> · <a href="/bot-arena">Bot Arena</a></main>;
  return <><header className="account-nav">Current user: {user.username} · <a href="/account">Account</a> · <a href="/bot-workspace">Bot Workspace</a> · <a href="/tournaments">Tournaments</a> <button onClick={() => void logout()}>Logout</button>{error && <p role="alert">{error}</p>}</header>
    {account ? <main className="auth"><h1>Account</h1><p>{user.username}</p><details><summary>Account details</summary><p>User ID: {user.id}</p></details></main> : <div key={user.id}>{children}</div>}</>;
}
export default function AuthPage({ register = false }: { register?: boolean }) {
  const session = useSession(), router = useNavigation();
  const [username, setUsername] = useState(''), [password, setPassword] = useState(''), [busy, setBusy] = useState(false), [message, setMessage] = useState('');
  const title = register ? 'Register' : 'Login';
  return <main className="auth"><PageHeader eyebrow="PLAYER ACCESS" title={title} description={register ? 'Create your local player account.' : 'Continue building your next strategy.'} /><form onSubmit={event => {
    event.preventDefault(); if (busy) return; setBusy(true); setMessage('');
    void localApi('auth/' + (register ? 'register' : 'login'), 'POST', { username, password }).then(() => {
      setPassword(''); if (register) setMessage('Account created. Đăng nhập để tiếp tục.'); else { notifyAuthChange(); if (router && session) { void session.refresh(); router.navigate('/dashboard'); } else window.location.assign('/bot-workspace'); }
    }).catch(e => { setPassword(''); setMessage((e as Error).message); }).finally(() => setBusy(false));
  }}><label>Username<Input required autoComplete="username" pattern="[a-z0-9_]{3,32}" minLength={3} maxLength={32} value={username} onChange={e => setUsername(e.target.value)} /></label>
    <label>Password<Input required type="password" autoComplete={register ? 'new-password' : 'current-password'} minLength={register ? 15 : 1} maxLength={72} value={password} onChange={e => setPassword(e.target.value)} /></label>
    {register && <p>3–32 lowercase letters/digits/underscores. Password: at least 15 characters, maximum 72 UTF-8 bytes. {safeStaging ? 'Staging: bot execution disabled.' : 'Local development only.'}</p>}
    <Button disabled={busy}>{busy ? 'Loading…' : title}</Button></form>
    {message && <p role="alert">{message}</p>}<Link href={register ? '/login' : '/register'}>{register ? 'Login' : 'Register'}</Link> · <Link href="/bot-arena">Bot Arena</Link></main>;
}
