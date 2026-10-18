import { playerToolsEnabled } from './runtime';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, confirmNavigation, useNavigation } from './navigation';
import { useSession } from './session';
import { Button, ErrorState, LoadingState, Modal } from './ui';
const playerLinks = [['/dashboard', 'Overview'], ['/my-bots', 'My Bots'], ['/bot-workspace', 'Bot Editor'], ['/bot-arena', 'Arena'], ['/my-tournaments', 'My Tournaments'], ['/match-history', 'Match History'], ['/account', 'Account'], ['/rankings', 'Rankings'], ['/ranked', 'Ranked']];
const publicLinks = [['/', 'Home'], ['/games', 'Games & Rules'], ['/tournaments', 'Tournaments'], ['/docs', 'Documentation']];
export function UserMenu({ account = false }: { account?: boolean }) {
    const session = useSession(), [error, setError] = useState(''), [busy, setBusy] = useState(false);
    if (!session?.user)
        return <div className="user-actions"><Link href="/login">Login</Link><Link className="ui-button primary" href="/register">Get started</Link></div>;
    return <div className="user-actions">{!account && <Link href="/account" className="user-name">{session.user.username}</Link>}<Button variant={account ? "primary" : "secondary"} disabled={busy} onClick={() => { if (!confirmNavigation())
        return; setBusy(true); void session.logout().catch(e => setError(e.message)).finally(() => setBusy(false)); }}>Logout</Button>{error && <span role="alert">{error}</span>}</div>;
}
export function Header({ onMenu }: {
    onMenu: () => void;
}) { return <header className="site-header"><Link href="/" className="brand"><span className="brand-mark">B<span>∶</span></span><span>BATTLECODE<small>MINI PLATFORM</small></span></Link><nav aria-label="Public navigation">{publicLinks.slice(1).map(([href, label]) => <Link key={href} href={href}>{label}</Link>)}</nav><UserMenu /><Button className="mobile-toggle" variant="secondary" aria-label="Open navigation" onClick={onMenu}>☰</Button></header>; }
export function Sidebar({ collapsed, onToggle }: {
    collapsed: boolean;
    onToggle: () => void;
}) {
    const router = useNavigation();
    return <aside className={`app-sidebar ${collapsed ? 'collapsed' : ''}`}><div className="sidebar-caption">Navigation<Button variant="secondary" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={onToggle}>{collapsed ? '»' : '«'}</Button></div><nav aria-label="Player navigation">{playerLinks.map(([href, label], index) => <Link href={href} key={href} title={label} aria-current={router?.path.split('?')[0] === href ? 'page' : undefined}><span className="nav-icon" aria-hidden="true">{['⌂', '◇', '⌘', '◉', '⚑', '≡', '◎', '★'][index]}</span><span className="nav-label">{label}</span></Link>)}</nav></aside>;
}
export function MobileNavigation({ open, onClose }: {
    open: boolean;
    onClose: () => void;
}) {
    const router = useNavigation();
    useEffect(() => { onClose(); }, [router?.path]);
    return <Modal title="Navigation" open={open} onClose={onClose}><nav className="mobile-links" aria-label="Mobile navigation">{[...publicLinks, ...playerLinks].map(([href, label]) => <Link key={href} href={href} onClick={onClose}>{label}</Link>)}</nav><UserMenu /></Modal>;
}
export function Breadcrumbs() {
 const path = useNavigation()?.path.split('?')[0] ?? '/';
 if (path !== '/bot-workspace' && !path.startsWith('/tournaments/')) return null;
 return <nav className="breadcrumbs" aria-label="Breadcrumb"><Link href={path === '/bot-workspace' ? '/my-bots' : '/tournaments'}>{path === '/bot-workspace' ? 'My Bots' : 'Tournaments'}</Link><span aria-hidden="true">/</span><span>{path === '/bot-workspace' ? 'Editor' : 'Detail'}</span></nav>;
}
function Shell({ children, player }: {
    children: ReactNode;
    player: boolean;
}) {
    const [collapsed, setCollapsed] = useState(false), [mobile, setMobile] = useState(false);
    return <div className={`platform ${player ? 'player-shell' : 'public-shell'}`}><a className="skip-link" href="#page-content">Skip to content</a><Header onMenu={() => setMobile(true)}/><div className="shell-body">{player && <Sidebar collapsed={collapsed} onToggle={() => setCollapsed(v => !v)}/>}<div id="page-content" className="page-content" tabIndex={-1}><Breadcrumbs />{children}<footer className="site-footer"><span>MINI BATTLECODE</span><Link href="/docs">Build. Submit. Compete.</Link></footer></div></div><MobileNavigation open={mobile} onClose={() => setMobile(false)}/></div>;
}
export function AppLayout({ children }: {
    children: ReactNode;
}) { return <Shell player>{children}</Shell>; }
export function PublicLayout({ children }: {
    children: ReactNode;
}) { return <Shell player={false}>{children}</Shell>; }
export function AuthGuard({ children }: {
    children: ReactNode;
}) {
    const session = useSession();
    if (!playerToolsEnabled)
        return <ErrorState>Player tools hiện chỉ bật trong local development. <Link href="/multiplayer">Open multiplayer</Link></ErrorState>;
    if (session?.loading)
        return <LoadingState>Đang kiểm tra phiên đăng nhập…</LoadingState>;
    if (!session?.user)
        return <section className="sign-in-state"><h1>Sign in to continue</h1><ErrorState>{session?.error ?? 'Đăng nhập để truy cập tài khoản.'}</ErrorState><Link className="ui-button primary" href="/login">Login</Link> <Link href="/register">Create account</Link></section>;
    return <div key={session.user.id}>{children}</div>;
}
