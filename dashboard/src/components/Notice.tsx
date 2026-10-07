import type { ReactNode } from "react";

/** A message about the page as a whole: information, a warning, or an error. */
export function Notice({ tone, children }: { tone: "info" | "warning" | "error"; children: ReactNode }) {
  return (
    <div className={`notice notice-${tone}`} role={tone === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}
