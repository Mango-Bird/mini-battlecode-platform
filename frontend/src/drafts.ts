import type { BotDetail } from './local-submissions';
type Draft = {
    selected: BotDetail | null;
    name: string;
    source: string;
};
// Volatile, bounded and scoped to server identity. Never written to browser storage.
const drafts = new Map<string, Draft>();
export function readDraft(ownerId: string) { return drafts.get(ownerId); }
export function keepDraft(ownerId: string, draft: Draft | null) {
    if (!draft) {
        drafts.delete(ownerId);
        return true;
    }
    if (new TextEncoder().encode(draft.source).length > 32768)
        return false;
    if (!drafts.has(ownerId) && drafts.size >= 16)
        return false;
    drafts.set(ownerId, { ...draft });
    return true;
}
