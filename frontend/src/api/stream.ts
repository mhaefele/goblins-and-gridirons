import type { DomainEvent } from "./client";

export type StreamFrame = {
    /** Per-match domain event id — used as `Last-Event-ID` on reconnect. */
    id: number;
    kind: string;
    event: DomainEvent;
};

export type MatchEventHandlers = {
    onEvent: (frame: StreamFrame) => void;
    onError?: (error: unknown) => void;
    onOpen?: () => void;
};

export type MatchEventSubscription = {
    close: () => void;
};

/**
 * Subscribe to `GET /api/matches/{id}/events` with optional `Last-Event-ID`.
 * Uses fetch streaming so the initial cursor can be set (native `EventSource` cannot).
 * Keepalive comments are ignored. Swap stays isolated here when Bearer auth lands.
 */
export function subscribeMatchEvents(
    matchId: string,
    lastEventId: number | null | undefined,
    handlers: MatchEventHandlers,
): MatchEventSubscription {
    const controller = new AbortController();
    const url = `/api/matches/${encodeURIComponent(matchId)}/events`;

    void (async () => {
        try {
            const headers: Record<string, string> = { Accept: "text/event-stream" };
            if (lastEventId != null && lastEventId > 0) {
                headers["Last-Event-ID"] = String(lastEventId);
            }
            const response = await fetch(url, { headers, signal: controller.signal });
            if (!response.ok || response.body == null) {
                handlers.onError?.(new Error(`SSE subscribe failed: HTTP ${response.status}`));
                return;
            }
            handlers.onOpen?.();
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = "";
            let currentId: string | null = null;
            let currentEvent: string | null = null;
            const dataLines: string[] = [];

            const flush = () => {
                if (currentId == null || currentEvent == null || dataLines.length === 0) {
                    currentId = null;
                    currentEvent = null;
                    dataLines.length = 0;
                    return;
                }
                const data = dataLines.join("\n");
                dataLines.length = 0;
                const id = Number.parseInt(currentId, 10);
                const kind = currentEvent;
                currentId = null;
                currentEvent = null;
                if (!Number.isFinite(id)) return;
                try {
                    const event = JSON.parse(data) as DomainEvent;
                    handlers.onEvent({ id, kind, event });
                } catch (error) {
                    handlers.onError?.(error);
                }
            };

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                let newline = buffer.indexOf("\n");
                while (newline >= 0) {
                    let line = buffer.slice(0, newline);
                    buffer = buffer.slice(newline + 1);
                    if (line.endsWith("\r")) line = line.slice(0, -1);
                    if (line === "") {
                        flush();
                    } else if (line.startsWith(":")) {
                        // keepalive
                    } else if (line.startsWith("id:")) {
                        currentId = line.slice(3).trim();
                    } else if (line.startsWith("event:")) {
                        currentEvent = line.slice(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataLines.push(line.slice(5).trimStart());
                    }
                    newline = buffer.indexOf("\n");
                }
            }
        } catch (error) {
            if (!controller.signal.aborted) {
                handlers.onError?.(error);
            }
        }
    })();

    return {
        close: () => controller.abort(),
    };
}
