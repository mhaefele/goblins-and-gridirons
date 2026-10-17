import { useEffect, useRef } from "react";
import type { EventLogEntry } from "../state/eventLog";

type EventLogProps = {
    entries: readonly EventLogEntry[];
};

export default function EventLog({ entries }: EventLogProps) {
    const listRef = useRef<HTMLUListElement>(null);

    useEffect(() => {
        const list = listRef.current;
        if (list == null) return;
        list.scrollTop = list.scrollHeight;
    }, [entries.length]);

    return (
        <section className="event-log" aria-label="Event log" aria-live="polite" aria-relevant="additions">
            <h2 className="event-log__title">Event log</h2>
            {entries.length === 0 ? (
                <p className="event-log__empty">No events yet — take an action or wait for the other coach.</p>
            ) : (
                <ul className="event-log__list" ref={listRef}>
                    {entries.map((entry, index) => (
                        <li key={`${entry.id ?? "note"}-${index}-${entry.text}`} className="event-log__entry">
                            {entry.id != null ? <span className="event-log__id">{entry.id}.</span> : null}
                            {entry.text}
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
