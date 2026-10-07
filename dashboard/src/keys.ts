// Project, environment and flag keys: the format the database checks (docs/DATABASE.md).

/** The pattern, for an input's `pattern` attribute, which anchors it itself. */
export const KEY_PATTERN = "[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?";

export const KEY_HINT = "Lowercase letters, digits and hyphens, up to 63 characters.";

const KEY = new RegExp(`^${KEY_PATTERN}$`);

export function isKey(value: string): boolean {
  return KEY.test(value);
}

/** A key suggested from a name: "New checkout" becomes "new-checkout". */
export function keyFrom(name: string): string {
  return name
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 63)
    .replace(/-+$/, "");
}
