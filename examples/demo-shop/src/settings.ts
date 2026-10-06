// Everything the shop and its scripts read from the environment, in one place. npm runs every
// script with --env-file-if-exists=.env, and a variable already set in the shell wins over .env.

export const NEW_CHECKOUT = "new-checkout";
export const FREE_SHIPPING = "free-shipping";

/** The environment the demo runs against. Every project is created with this one. */
export const ENVIRONMENT = "development";

export const flaglaneUrl = (process.env.FLAGLANE_URL ?? "http://localhost:8080").replace(/\/+$/, "");
export const projectKey = process.env.FLAGLANE_DEMO_PROJECT ?? "demo-shop";
export const port = Number(process.env.PORT ?? 3000);
export const shopUrl = `http://localhost:${port}`;

export function sdkKey(): string {
  return process.env.FLAGLANE_SDK_KEY ?? "";
}

export function account(): { email: string; password: string } {
  const email = process.env.FLAGLANE_DEMO_EMAIL;
  const password = process.env.FLAGLANE_DEMO_PASSWORD;
  if (email === undefined || email === "" || password === undefined || password === "") {
    throw new Error("No demo account in .env. Run npm run seed first.");
  }
  return { email, password };
}
