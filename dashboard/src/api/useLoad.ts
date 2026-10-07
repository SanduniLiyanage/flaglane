import { useCallback, useEffect, useState } from "react";
import { ApiError } from "./client";

export type Load<T> =
  | { readonly state: "loading" }
  | { readonly state: "loaded"; readonly data: T }
  | { readonly state: "failed"; readonly error: ApiError };

/**
 * Loads `load()` when `key` changes, and again on `reload()`. An answer that arrives after the key
 * has moved on is dropped, so switching environments quickly never shows the previous one's data.
 */
export function useLoad<T>(key: string, load: () => Promise<T>): Load<T> & { reload: () => void } {
  const [result, setResult] = useState<Load<T>>({ state: "loading" });
  const [generation, setGeneration] = useState(0);
  const reload = useCallback(() => setGeneration((g) => g + 1), []);

  useEffect(() => {
    let current = true;
    setResult({ state: "loading" });
    load().then(
      (data) => current && setResult({ state: "loaded", data }),
      (error: unknown) =>
        current &&
        setResult({
          state: "failed",
          error: error instanceof ApiError ? error : new ApiError(0, "Something went wrong in the dashboard."),
        }),
    );
    return () => {
      current = false;
    };
    // `load` is a new function on every render; `key` is what says the data has changed.
  }, [key, generation]);

  return { ...result, reload };
}
