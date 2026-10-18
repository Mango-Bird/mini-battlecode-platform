import { Link } from './navigation';
export type Piece = { id: string; owner: string; type: string; position: { row: number; col: number } };
export type Move = { number: number; side: string; pieceId: string; from: Piece['position']; to: Piece['position']; captured: Piece | null };
export type Match = { matchId: string; kind: string; gameMode: string; rulesVersion: string; status: string; date: string;
  a: { botId: string | null; name: string; version: number; ownerDisplay?: string | null; eloBefore: number | null }; b: Match['a'];
  snapshot: { status: string; turn: number; currentSide: string; board: Piece[]; moves: Move[]; frames: Piece[][]; result: { outcome: string; winner: string | null; reason: string } | null } | null;
  ratingChanges: { botId: string; before: number; after: number }[] };
export function BotLink({ bot }: { bot: Match['a'] }) { return bot.botId ? <Link href={'/bots/' + bot.botId}>{bot.name}</Link> : <span>{bot.name}</span>; }
export function terminal(m: Match) { return ['COMPLETED', 'FINISHED', 'DRAW_REPLAY', 'CANCELLED', 'INTERRUPTED'].includes(m.status); }
export function replayValid(s: Match['snapshot']) {
  if (!s || !Array.isArray(s.moves) || !Array.isArray(s.frames) || !Array.isArray(s.board) || s.moves.length > 1000 || s.frames.length !== s.moves.length + 1) return false;
  const valid = (board: Piece[]) => Array.isArray(board) && board.length <= 12 && new Set(board.map(p => p.id)).size === board.length && new Set(board.map(p => `${p.position?.row}:${p.position?.col}`)).size === board.length && board.every(p => ['A', 'B'].includes(p.owner) && ['ROCK', 'PAPER', 'SCISSORS'].includes(p.type) && Number.isInteger(p.position?.row) && Number.isInteger(p.position?.col) && p.position.row >= 0 && p.position.row < 9 && p.position.col >= 0 && p.position.col < 9);
  return s.frames.every(valid) && valid(s.board) && JSON.stringify(s.frames.at(-1)) === JSON.stringify(s.board) && s.moves.every((m, i) => m.number === i + 1 && [m.from, m.to].every(p => p && Number.isInteger(p.row) && Number.isInteger(p.col) && p.row >= 0 && p.row < 9 && p.col >= 0 && p.col < 9));
}
export function RatingChart({ history }: { history: { eloAfter: number }[] }) {
  if (history.length < 2) return null; const values = [...history].reverse().map(e => e.eloAfter), lo = Math.min(...values), span = Math.max(1, Math.max(...values) - lo);
  return <svg className="rating-chart" viewBox="0 0 400 100" role="img" aria-label="Confirmed Elo history"><polyline fill="none" stroke="currentColor" strokeWidth="2" points={values.map((v, i) => `${i * 400 / (values.length - 1)},${90 - (v - lo) * 80 / span}`).join(' ')} /></svg>;
}
