import { fetchEventSource } from "@microsoft/fetch-event-source";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import type { StatusChange } from "./api";
import { accessToken } from "./auth";

export type LiveState = "connecting" | "live" | "reconnecting";

/**
 * The company's work order changes, as they happen (server-sent events, ADR 0007). EventSource can't send a
 * bearer token, so this uses fetch. Every (re)connect asks for a fresh token, and after a reconnect the
 * lists reload once: events sent while disconnected aren't replayed, the reload covers them.
 */
export function useLiveUpdates(onChange: (change: StatusChange) => void) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<LiveState>("connecting");
  const [lastEventAt, setLastEventAt] = useState<Date | null>(null);
  const handler = useRef(onChange);
  handler.current = onChange;

  useEffect(() => {
    const abort = new AbortController();
    let wasConnected = false;
    void fetchEventSource("/api/live/work-orders", {
      signal: abort.signal,
      openWhenHidden: true,
      fetch: async (input, init) => {
        const headers = new Headers(init?.headers);
        headers.set("Authorization", `Bearer ${await accessToken()}`);
        return fetch(input, { ...init, headers });
      },
      onopen: async (response) => {
        if (!response.ok) {
          throw new Error(`Live updates: HTTP ${response.status}`);
        }
        // After a reconnect, reload what's on screen (events sent meanwhile are gone); on any connect, retry
        // whatever failed while the server was unreachable.
        void queryClient.invalidateQueries({
          predicate: (query) => (wasConnected && query.queryKey[0] === "work-orders") || query.state.status === "error",
        });
        wasConnected = true;
        setState("live");
      },
      onmessage: (message) => {
        if (message.event !== "work-order.status-changed") {
          return;
        }
        setLastEventAt(new Date());
        handler.current(JSON.parse(message.data) as StatusChange);
      },
      onclose: () => {
        setState("reconnecting"); // the server closes after 30 minutes on purpose; reconnect
        throw new Error("closed");
      },
      onerror: () => {
        setState("reconnecting");
        return 3000; // retry in 3 s
      },
    });
    return () => abort.abort();
  }, [queryClient]);

  return { state, lastEventAt };
}
