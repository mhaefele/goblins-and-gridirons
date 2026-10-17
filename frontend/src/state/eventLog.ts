/** One line in the play event log. */
export type EventLogEntry = {
    /** Domain stream id for folded events; null for UI-only notes. */
    id: number | null;
    text: string;
};
