// The environment last opened in each project, so that a page about the whole project, such as the
// audit trail, can link back to flags and keys in the same environment. Held in memory with the
// session, so it lasts as long as the tab's session does (ADR-031).

const remembered = new Map<string, string>();

export function rememberEnvironment(project: string, environment: string): void {
  remembered.set(project, environment);
}

export function lastEnvironment(project: string): string | undefined {
  return remembered.get(project);
}
