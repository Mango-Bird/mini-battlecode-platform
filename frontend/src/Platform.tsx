import App from './App';
import AuthPage from './Auth';
import BotArena from './BotArena';
import BotWorkspace from './BotWorkspace';
import Tournaments from './Tournaments';
import Rankings from './Rankings';
import Ranked from './Ranked';
import MatchViewer from './MatchViewer';
import BotProfile from './BotProfile';
import CompetitiveHistory from './CompetitiveHistory';
import { Router, useNavigation, Link } from './navigation';
import { SessionProvider, useSession } from './session';
import { AppLayout, PublicLayout, AuthGuard } from './layouts';
import { Home, Games, Documentation, Account, DataPage } from './pages';
import { EmptyState, ErrorBoundary, PageHeader } from './ui';
import { safeStaging, executionUnavailable } from './runtime';
import './platform.css';
import { useEffect } from 'react';
function Routes() {
    const route = useNavigation()?.path ?? '/', path = route.split('?')[0], session = useSession();
    const query = new URLSearchParams(route.split('?')[1]);
    useEffect(() => { document.title = `Mini Battlecode · ${path === '/' ? 'Home' : path.slice(1).split('/')[0].replaceAll('-', ' ')}`; document.getElementById('page-content')?.focus({ preventScroll: true }); }, [path]);
    let page;
    let player = ['/dashboard', '/my-bots', '/bot-workspace', '/bot-arena', '/arena', '/my-tournaments', '/match-history', '/account', '/rankings', '/ranked'].includes(path);
    if (/^\/(matches|bots)\/[^/]+$/.test(path)) player = true;
    if (path === '/')
        page = <Home />;
    else if (path === '/games')
        page = <Games />;
    else if (path === '/docs')
        page = <Documentation />;
    else if (path === '/login' || path === '/register')
        page = <AuthPage key={path} register={path === '/register'}/>;
    else if (path === '/multiplayer')
        page = <div className="multiplayer"><App /></div>;
    else if (path === '/dashboard')
        page = <DataPage kind="dashboard"/>;
    else if (path === '/my-bots')
        page = <DataPage kind="bots"/>;
    else if (path === '/match-history')
        page = <CompetitiveHistory />;
    else if (/^\/matches\/[^/]+$/.test(path))
        page = <MatchViewer key={path} id={path.split('/')[2]} />;
    else if (/^\/bots\/[^/]+$/.test(path))
        page = <BotProfile key={path} id={path.split('/')[2]} />;
    else if (path === '/ranked')
        page = <Ranked />;
    else if (path === '/rankings')
        page = <Rankings />;
    else if (path === '/account')
        page = <Account />;
    else if (path === '/bot-workspace')
        page = <BotWorkspace initialBotId={query.get('bot') ?? undefined}/>;
    else if (path === '/bot-arena' || path === '/arena')
        page = <BotArena initialBattleId={query.get('battle') ?? undefined}/>;
    else if (path === '/tournaments' || path === '/my-tournaments' || /^\/tournaments\/[^/]+$/.test(path)) {
        player = !!session?.user || path === '/my-tournaments';
        page = session?.user || path === '/my-tournaments' ? <Tournaments key={path} mine={path === '/my-tournaments'} initialTournamentId={path.startsWith('/tournaments/') ? path.split('/')[2] : undefined}/> : <main><PageHeader eyebrow="COMPETE" title="Tournaments" description="Round Robin & Single Elimination"/><EmptyState title="Sign in to browse tournaments"><p>Đăng nhập để xem và tham gia giải đấu.</p><Link className="ui-button primary" href="/login">Login</Link></EmptyState></main>;
    }
    else
        page = <main><EmptyState title="Page not found"><Link href="/">Return home</Link></EmptyState></main>;
    if (safeStaging && ['/bot-arena', '/arena', '/ranked', '/my-tournaments', '/tournaments', '/multiplayer'].some(p => path === p || path.startsWith(p + '/')))
        page = <main><PageHeader title="Execution unavailable"/><p role="status">{executionUnavailable}</p><Link href="/my-bots">My Bots</Link></main>;
    if (safeStaging && path === '/dashboard') page = <main><PageHeader title="Staging workspace"/><p>{executionUnavailable}</p><Link className="ui-button primary" href="/my-bots">My Bots</Link> <Link href="/match-history">Match History</Link></main>;
    const content = <ErrorBoundary key={path}>{safeStaging && <p role="note">Safe staging · execution disabled</p>}{player ? <AuthGuard>{page}</AuthGuard> : page}</ErrorBoundary>;
    return player ? <AppLayout>{content}</AppLayout> : <PublicLayout>{content}</PublicLayout>;
}
export default function Platform() { return <Router><SessionProvider><Routes /></SessionProvider></Router>; }
