import { api } from "../api/client";
import { useLoad } from "../api/useLoad";
import { navigate, useLocation } from "../router";

/**
 * Switches the page to the same place in another environment: the flag list stays the flag list,
 * and a flag's detail stays on that flag. Leaving unsaved changes asks first.
 */
export function EnvironmentSwitcher({ project, current }: { project: string; current: string }) {
  const location = useLocation();
  const environments = useLoad(project, () =>
    api("GET /api/projects/{projectKey}/environments", { projectKey: project }),
  );

  const switchTo = (environment: string) => {
    const known = environments.state === "loaded" ? environments.data.map((env) => env.key) : [];
    const to = inEnvironment(location, environment, known);
    if (to !== null) {
      navigate(to);
    }
  };

  const options = environments.state === "loaded" ? environments.data : [];
  return (
    <label className="environment">
      <span className="muted">Environment</span>
      <select
        value={current}
        onChange={(event) => switchTo(event.target.value)}
        disabled={environments.state !== "loaded"}
      >
        {!options.some((environment) => environment.key === current) && <option value={current}>{current}</option>}
        {options.map((environment) => (
          <option key={environment.key} value={environment.key}>
            {environment.name.toLowerCase() === environment.key ? environment.name : `${environment.name} (${environment.key})`}
          </option>
        ))}
      </select>
    </label>
  );
}

/**
 * The same page in another environment, `/projects/{project}/{environment}/...`, or null when there
 * is nowhere to go: the environment is the current one, or not one the project has, which would
 * build an address with no page behind it.
 */
export function inEnvironment(location: string, environment: string, environments: readonly string[]): string | null {
  const [pathname = "", query] = location.split("?");
  if (!environments.includes(environment) || pathname.split("/")[3] === encodeURIComponent(environment)) {
    return null;
  }
  const segments = pathname.split("/");
  segments[3] = encodeURIComponent(environment);
  return segments.join("/") + (query === undefined ? "" : `?${query}`);
}
