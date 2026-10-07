import { useEffect } from "react";
import { Layout } from "./components/Layout";
import { SignInPage } from "./pages/SignInPage";
import { Link, match, navigate, useLocation } from "./router";
import { useSession } from "./session/session";

/**
 * Where to go after signing in, from the sign-in page's `next`: a path on this origin, or the
 * project list. Anything else, such as `//elsewhere.example`, is ignored rather than followed.
 */
export function nextFrom(location: string): string {
  const query = location.split("?")[1] ?? "";
  const next = new URLSearchParams(query).get("next");
  if (next === null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return "/projects";
  }
  return next;
}

/** The sign-in page's address, returning to `location` afterwards. */
export function signInFor(location: string): string {
  return location === "/" || location.startsWith("/sign-in") ? "/sign-in" : `/sign-in?next=${encodeURIComponent(location)}`;
}

export function App() {
  const { session, ended } = useSession();
  const location = useLocation();
  const onSignIn = location === "/sign-in" || location.startsWith("/sign-in?");

  useEffect(() => {
    if (session === null && !onSignIn) {
      navigate(signInFor(location), { replace: true });
    } else if (session !== null && onSignIn) {
      navigate(nextFrom(location), { replace: true });
    } else if (session !== null && location === "/") {
      navigate("/projects", { replace: true });
    }
  }, [session, onSignIn, location]);

  if (session === null) {
    return onSignIn ? (
      <SignInPage ended={ended} next={nextFrom(location)} onSignedIn={(next) => navigate(next, { replace: true })} />
    ) : null;
  }
  return <Route location={location} />;
}

function Route({ location }: { location: string }) {
  if (match("/projects", location) !== null || match("/", location) !== null) {
    return (
      <Layout>
        <h1>Projects</h1>
      </Layout>
    );
  }
  if (match("/sign-in", location) !== null) {
    return null;
  }
  return (
    <Layout>
      <h1>Not found</h1>
      <p>
        There is no page at <code>{location}</code>. <Link to="/projects">Go to your projects.</Link>
      </p>
    </Layout>
  );
}
