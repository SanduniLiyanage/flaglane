// Path-based routing over the History API, about as much of a router as five routes need. The URL
// carries the project, the environment and the flag (ADR-031), so a reload, which signs the user
// out, costs a password and nothing more: signing in returns to the same URL.

import { useEffect, useSyncExternalStore, type AnchorHTMLAttributes, type MouseEvent } from "react";

const listeners = new Set<() => void>();
let guard: string | null = null;
let shown = typeof window === "undefined" ? "/" : here();

function here(): string {
  return window.location.pathname + window.location.search;
}

function notify(): void {
  shown = here();
  for (const listener of listeners) {
    listener();
  }
}

/** Asks before leaving a page with unsaved changes; true to go on. */
function mayLeave(): boolean {
  return guard === null || window.confirm(guard);
}

if (typeof window !== "undefined") {
  window.addEventListener("popstate", () => {
    // Back and forward cannot be cancelled, only undone: put the page that was left back.
    if (!mayLeave()) {
      window.history.pushState(null, "", shown);
      return;
    }
    notify();
  });
}

/**
 * Asks before something other than navigation leaves the page, such as signing out; true to go on.
 * Once confirmed, the page's guard has done its job, and the next page sets its own.
 */
export function confirmLeaving(): boolean {
  if (!mayLeave()) {
    return false;
  }
  guard = null;
  return true;
}

export function navigate(to: string, options: { replace?: boolean } = {}): void {
  if (to === here()) {
    return;
  }
  if (!confirmLeaving()) {
    return;
  }
  if (options.replace === true) {
    window.history.replaceState(null, "", to);
  } else {
    window.history.pushState(null, "", to);
  }
  notify();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The current path and query string. */
export function useLocation(): string {
  return useSyncExternalStore(subscribe, () => shown, () => "/");
}

/**
 * While `message` is not null, leaving the page asks first: navigating within the dashboard, going
 * back or forward, and reloading or closing the tab.
 */
export function useLeaveGuard(message: string | null): void {
  useEffect(() => {
    if (message === null) {
      return;
    }
    guard = message;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Browsers show their own wording; setting returnValue is what makes them ask at all.
      event.returnValue = message;
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => {
      if (guard === message) {
        guard = null;
      }
      window.removeEventListener("beforeunload", beforeUnload);
    };
  }, [message]);
}

/**
 * The parameters of `pattern` in `path`, or null where it does not match. A segment written
 * `:name` matches any one segment; everything else matches itself.
 */
export function match(pattern: string, path: string): Record<string, string> | null {
  const [pathname = ""] = path.split("?");
  const want = pattern.split("/").filter(Boolean);
  const got = pathname.split("/").filter(Boolean);
  if (want.length !== got.length) {
    return null;
  }
  const params: Record<string, string> = {};
  for (let i = 0; i < want.length; i++) {
    const segment = want[i] as string;
    const actual = got[i] as string;
    if (segment.startsWith(":")) {
      let decoded: string;
      try {
        decoded = decodeURIComponent(actual);
      } catch {
        return null;
      }
      params[segment.slice(1)] = decoded;
    } else if (segment !== actual) {
      return null;
    }
  }
  return params;
}

/** Builds a path from segments, encoding each. */
export function path(...segments: string[]): string {
  return `/${segments.map(encodeURIComponent).join("/")}`;
}

type LinkProps = Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href"> & { to: string };

/** An anchor that navigates in place, and still opens in a new tab when asked to. */
export function Link({ to, onClick, ...rest }: LinkProps) {
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event);
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey ||
      (rest.target !== undefined && rest.target !== "_self")
    ) {
      return;
    }
    event.preventDefault();
    navigate(to);
  };
  return <a href={to} onClick={follow} {...rest} />;
}
