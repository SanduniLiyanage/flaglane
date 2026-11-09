# Deploying Flaglane

How to run Flaglane for a team: one container on a host that puts it behind HTTPS, and one managed
PostgreSQL 16 database. `docker compose up` in the README is the same image against a local
database, for trying it out.

## What you run

- **One API container**, built from `backend/Dockerfile`. It serves the management API, the serving
  API and the dashboard from one origin, on port 8080 (ADR-031). It keeps nothing on disk.
- **One PostgreSQL 16 database**, which holds everything: projects, flags, rules, hashed API keys
  and the audit trail.
- **A reverse proxy in front that terminates TLS.** Most container hosts provide one. API keys and
  dashboard tokens are bearer credentials, sent on every request, so plain HTTP is not an option.

Run **exactly one** API container. Each instance holds the rulesets, the API keys and the rate
limits in its own memory, and in v0.1 nothing tells one instance about a change made through
another: a second instance serves rulesets up to a minute stale and keeps accepting a key revoked on
the first until it restarts (ADR-013, ADR-036).

From v0.1.0, every release is published as **`ghcr.io/sanduniliyanage/flaglane`**, for
`linux/amd64` and `linux/arm64` (ADR-038):

| Tag | Points to |
| --- | --- |
| `0.1.0` | That release, and never anything else |
| `0.1` | The latest patch release of 0.1 |
| `latest` | The latest release |

Deploy a full version such as `0.1.0`. Migrations run when the container starts and only go
forward (section 5), so a tag that moves would upgrade you, irreversibly, the next time the host
restarted the container. Each image carries a provenance attestation, which records the commit and
workflow that built it, and an SBOM listing what it contains:

```bash
docker buildx imagetools inspect ghcr.io/sanduniliyanage/flaglane:0.1.0 --format '{{ json .Provenance }}'
docker buildx imagetools inspect ghcr.io/sanduniliyanage/flaglane:0.1.0 --format '{{ json .SBOM }}'
```

To build the same image from a clone instead:

```bash
docker build --file backend/Dockerfile --tag flaglane:0.1.0 .
```

## 1. The database

Flaglane connects as two roles: `flaglane_migrator` owns the schema and runs the migrations at
startup, and `flaglane_app` runs the application, with no DDL privileges and no way to change or
delete audit entries (ADR-016, FR-AUD-002). The roles and their passwords are created by you, not by
a migration, so no credential is ever in the source. Choose two long random passwords, then, as your
provider's administrative user:

```sql
create role flaglane_migrator login password '<migrator password>';
create role flaglane_app login password '<app password>';

-- PostgreSQL 16 lets a user that is not a superuser give a database to a role only if it may act
-- as that role. Harmless if you are a superuser.
grant flaglane_migrator to current_user;
create database flaglane owner flaglane_migrator;
```

Then, connected to the new `flaglane` database:

```sql
grant connect on database flaglane to flaglane_app;
grant usage on schema public to flaglane_app;
```

That is all. The first migration creates every table, and the second grants `flaglane_app` what it
needs on them; a role that does not exist fails that migration with its name in the error. If your
provider will not let you create a database, give the one it made you to `flaglane_migrator` with
`alter database <name> owner to flaglane_migrator` and run the two grants in it.

**Use a direct connection, not a pooler in transaction mode.** Many managed services offer both.
Flyway holds a session-level advisory lock while it migrates, and the driver prepares statements on
the server after a few executions; a transaction-mode pooler hands each transaction to whichever
server connection is free, so both break. A pooler in session mode works; a direct connection is
simpler. The application keeps a pool of up to ten connections and Flyway opens its own at startup,
so allow at least twenty on the database.

**Connect over TLS.** Put the options in the JDBC URL:

```
jdbc:postgresql://<host>:5432/flaglane?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
```

`verify-full` checks the server's certificate and that it names the host. With
`DefaultJavaSSLFactory` the certificate is checked against the JVM's trust store, which is right when
your provider's certificate is signed by a public certificate authority. If it is signed by the
provider's own authority, put that certificate in the container instead and name it with
`sslrootcert=<path>` in place of `sslfactory`. `sslmode=require` encrypts without checking who
answered and is not enough on a network you do not control.

## 2. The container

Configuration comes from environment variables only. Nothing has a default that is a credential.

| Variable | Required | Meaning |
| --- | --- | --- |
| `FLAGLANE_DB_URL` | Yes | The JDBC URL above |
| `FLAGLANE_DB_MIGRATOR_PASSWORD` | Yes | `flaglane_migrator`'s password |
| `FLAGLANE_DB_APP_PASSWORD` | Yes | `flaglane_app`'s password |
| `FLAGLANE_JWT_SECRET` | Yes | Signs dashboard access tokens; at least 32 bytes, from `openssl rand -base64 48`. Changing it signs everyone out |
| `FLAGLANE_FORWARDED_HEADERS` | Behind a proxy | `native`; see the next section. `none` by default |
| `FLAGLANE_DB_MIGRATOR_USER` | No | `flaglane_migrator` by default |
| `FLAGLANE_DB_APP_USER` | No | `flaglane_app` by default |
| `FLAGLANE_AUTH_RATE_LIMIT_PER_MINUTE` | No | Registration and sign-in requests a minute per client address; 10 by default (ADR-029) |
| `FLAGLANE_SDK_RATE_LIMIT_PER_MINUTE` | No | Serving API requests a minute per API key, a `304` counting as a tenth; 600 by default, which serves 500 SDK processes polling one key (ADR-035) |

A missing required variable stops the application at startup with the variable named. The JVM sizes
its heap from the container's memory limit, so set one.

On a host that runs containers from a dashboard, enter the image and the variables there. On a
machine of your own, with the proxy on the same machine:

```bash
docker run --detach --name flaglane --restart unless-stopped \
  --env-file flaglane.env \
  --publish 127.0.0.1:8080:8080 \
  --memory 1g \
  ghcr.io/sanduniliyanage/flaglane:0.1.0
```

`flaglane.env` holds the variables, one `NAME=value` per line; make it readable by you alone.
Publishing on `127.0.0.1` lets the proxy reach the container and nothing else on the network,
which section 3 depends on.

## 3. Behind the proxy

Set `FLAGLANE_FORWARDED_HEADERS=native`. Without it every request seems to come from the proxy, so
all your users share one sign-in allowance of ten a minute, and the application cannot tell that a
request arrived over HTTPS (ADR-037). With it, the application reads the client's address from
`X-Forwarded-For` and the scheme from `X-Forwarded-Proto`, which every common proxy and host load
balancer sends.

It believes those headers only from a peer on a private address — loopback, link-local, 10/8,
172.16/12, 192.168/16, 100.64/10 or an IPv6 unique local address — which is where a host's proxy
connects from. Two things follow:

- **Nothing but the proxy may reach the container on a private address.** A client that can, could
  write its own `X-Forwarded-For` and claim any address it likes. Publish the container's port to
  the proxy only.
- **A proxy that connects from a public address must be named.** Set
  `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regular expression matching its addresses, for
  example `203\.0\.113\.\d{1,3}`. This replaces the private ranges rather than adding to them.

Leave it at `none` when nothing sits in front of the container, as on the Compose stack.

## 4. Health checks

| Path | Use it for | Reports |
| --- | --- | --- |
| `/actuator/health/liveness` | Restarting the container | Whether the process is running. Never the database |
| `/actuator/health/readiness` | Sending it traffic | Whether every environment's ruleset is loaded. Never the database |
| `/actuator/health` | Looking at, not automating | Everything, the database included: `503` while the database is down |

Do not point an orchestrator at `/actuator/health`. While the database is down, the serving API keeps
answering from memory, and that is when a restart would take it away (NFR-REL-003). Readiness turns
`UP` once the migrations have run and the rulesets are built; on the Compose stack, within sixteen
seconds of the container starting.

## 5. Upgrading

Stop the running container, then start the new one. Do not run them side by side, as a rolling
or blue-green deploy would: for as long as both run, each misses the changes made through the
other. Many hosts start the new container before stopping the old one unless told otherwise; choose
the strategy they call recreate, or stop first.

- Migrations run when the new version starts, as `flaglane_migrator`, and only forward. Take a
  backup first; going back to an older version means restoring it.
- While no container is running, SDKs keep evaluating on the ruleset they hold and ask again with
  backoff, so applications carry on with the flags as they were. The dashboard is unavailable for
  the restart, and a dashboard user signs in again afterwards only if their tab was reloaded.

## 6. Backups

The database is the only state. Use your provider's backups, with point-in-time recovery if it
offers it. Restoring one brings back flags, keys and the audit trail together; an API key issued
after the backup was taken is gone with it and has to be issued again.

## What this does not give you

- **More than one instance**, for capacity or for deploys without a gap (ADR-036).
- **Server-side sign-out.** A dashboard token stays valid for eight hours, whatever happens in the
  browser (ADR-012).
- **A hosted instance.** The project runs none; you run your own (ADR-038).
