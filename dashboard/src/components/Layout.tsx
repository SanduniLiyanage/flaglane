import type { ReactNode } from "react";
import { confirmLeaving, Link } from "../router";
import { sessions, useSession } from "../session/session";

interface Props {
  readonly children: ReactNode;
}

export function Layout({ children }: Props) {
  const { session } = useSession();

  const signOut = () => {
    if (confirmLeaving()) {
      sessions.end("signed-out");
    }
  };

  return (
    <>
      <header className="top">
        <nav className="crumbs" aria-label="Location">
          <Link to="/projects" className="brand">
            Flaglane
          </Link>
        </nav>
        <div className="account">
          <span className="muted">{session?.email}</span>
          <button type="button" onClick={signOut} title="Discards your token in this tab">
            Sign out
          </button>
        </div>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
