// Changes a flag in the demo's environment, as the dashboard would: npm run flag -- rollout 60

import { type Config, Management } from "./management.ts";
import { ENVIRONMENT, NEW_CHECKOUT, account, projectKey } from "./settings.ts";

const usage = `Usage: npm run flag -- <command> [flag]

  rollout <0-100> [flag]  roll the flag out to this percentage of users
  off [flag]              the kill switch: the flag is off for everyone, overrides included
  on [flag]               switch it back on, with the rollout and rules it had
  show [flag]             print its configuration

The flag defaults to ${NEW_CHECKOUT}.`;

const [command, ...rest] = process.argv.slice(2);

try {
  let body: Partial<Config> | undefined;
  let flag = rest[0] ?? NEW_CHECKOUT;
  switch (command) {
    case "rollout": {
      const percentage = Number(rest[0]);
      if (rest[0] === undefined || !Number.isInteger(percentage) || percentage < 0 || percentage > 100) {
        throw new Error(`A rollout is a whole percentage from 0 to 100.\n\n${usage}`);
      }
      body = { rolloutPercentage: percentage };
      flag = rest[1] ?? NEW_CHECKOUT;
      break;
    }
    case "off":
      body = { enabled: false };
      break;
    case "on":
      body = { enabled: true };
      break;
    case "show":
      break;
    default:
      throw new Error(usage);
  }

  const { email, password } = account();
  const api = await Management.signIn(email, password);
  const path = `/api/projects/${projectKey}/flags/${flag}/config/${ENVIRONMENT}`;
  const config = await (body === undefined ? api.call<Config>("GET", path) : api.call<Config>("PATCH", path, body));
  console.log(
    `${config.flagKey} in ${config.environment}: ${config.enabled ? "on" : "OFF"}, ` +
      `rolled out to ${config.rolloutPercentage}%, otherwise ${config.fallthroughValue}`,
  );
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
