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
    const [pathname = "", query] = location.split("?");
    const segments = pathname.split("/");
    // /projects/{project}/{environment}/...
    segments[3] = encodeURIComponent(environment);
    navigate(segments.join("/") + (query === undefined ? "" : `?${query}`));
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
