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
function PasswordField({ id, label, value, onChange, register }: { id: string; label: string; value: string; onChange: (value: string) => void; register: boolean }) {
  const [visible, setVisible] = useState(false);
  return <div className="password-field"><label htmlFor={id}>{label}</label><div className="password-input">
    <Input id={id} required type={visible ? 'text' : 'password'} autoComplete={register ? 'new-password' : 'current-password'} minLength={register ? 8 : 1} maxLength={72} value={value} onChange={e => onChange(e.target.value)} />
    <button className="password-toggle" type="button" aria-label={`${visible ? 'Ẩn' : 'Hiện'} ${id === 'confirm-password' ? 'xác nhận mật khẩu' : 'mật khẩu'}`} aria-pressed={visible} aria-controls={id} onClick={() => setVisible(v => !v)}>
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>{visible && <path d="m3 3 18 18"/>}</svg>
    </button></div></div>;
}
export default function AuthPage({ register = false }: { register?: boolean }) {
  const session = useSession(), router = useNavigation();
  const [username, setUsername] = useState(''), [password, setPassword] = useState(''), [busy, setBusy] = useState(false), [message, setMessage] = useState('');
  const title = register ? 'Register' : 'Login';
  const [confirmPassword, setConfirmPassword] = useState('');
  return <main className="auth"><PageHeader eyebrow="PLAYER ACCESS" title={title} description={register ? 'Create your local player account.' : 'Continue building your next strategy.'} /><form onSubmit={event => {
    event.preventDefault(); if (busy) return;
    if (register && password.length < 8) { setMessage('Mật khẩu phải có ít nhất 8 ký tự.'); return; }
    if (register && new TextEncoder().encode(password).length > 72) { setMessage('Mật khẩu không được vượt quá 72 byte UTF-8.'); return; }
    if (register && password !== confirmPassword) { setMessage('Xác nhận mật khẩu không trùng khớp. Vui lòng nhập lại.'); return; }
    setBusy(true); setMessage('');
    void localApi('auth/' + (register ? 'register' : 'login'), 'POST', { username, password }).then(() => {
      setPassword(''); setConfirmPassword(''); if (register) setMessage('Account created. Đăng nhập để tiếp tục.'); else { notifyAuthChange(); if (router && session) { void session.refresh(); router.navigate('/dashboard'); } else window.location.assign('/bot-workspace'); }
    }).catch(e => { setMessage((e as Error).message); }).finally(() => setBusy(false));
  }}><label>Username<Input required autoComplete="username" pattern="[a-z0-9_]{3,32}" minLength={3} maxLength={32} value={username} onChange={e => setUsername(e.target.value)} /></label>
    <PasswordField id="password" label="Password" value={password} onChange={setPassword} register={register} />
    {register && <PasswordField id="confirm-password" label="Confirm Password" value={confirmPassword} onChange={setConfirmPassword} register />}
    {register && <p>Username: 3–32 chữ thường, chữ số hoặc dấu gạch dưới. Mật khẩu: ít nhất 8 ký tự, tối đa 72 byte UTF-8. {safeStaging ? 'Staging: bot execution disabled.' : 'Local development only.'}</p>}
    <Button disabled={busy}>{busy ? 'Loading…' : title}</Button></form>
    {message && <p role="alert">{message}</p>}<Link href={register ? '/login' : '/register'}>{register ? 'Login' : 'Register'}</Link> · <Link href="/bot-arena">Bot Arena</Link></main>;
}
