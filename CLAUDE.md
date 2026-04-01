## Git Rules — STRICT
- ALWAYS use native git for ALL commits and pushes
- NEVER use mcp__github__ tools for committing or pushing
- Use mcp__github__ ONLY for: PRs, Issues, GitHub Actions
- Write commit messages to a temp file, then: `git commit -F <file>`
- NEVER use --no-gpg-sign flag

# Cycles strict rules
- yaml API specs always the authority
- always update AUDIT.md files when making changes to server, admin, client repos
- maintain at least 95% or higher test coverage for all code repos

## Maven Builds

In Claude Code remote environments, use `mvn-proxy` instead of `mvn` for all Maven commands.
The session start hook (`.claude/session-start-maven-proxy.sh`) automatically sets this up.

```bash
# Use this:
mvn-proxy -B verify --file cycles-protocol-service/pom.xml

# NOT this (will fail with DNS/proxy errors):
mvn -B verify --file cycles-protocol-service/pom.xml
```

**Why:** The remote environment routes traffic through an egress proxy. Java's `JAVA_TOOL_OPTIONS`
proxy config resolves DNS locally (which fails). `mvn-proxy` uses `MAVEN_OPTS` instead and
forces single-threaded downloads to avoid proxy auth race conditions.

## Integration Tests

Integration tests require Docker (Testcontainers + Redis). Exclude them when Docker is unavailable:

```bash
mvn-proxy -B verify --file cycles-protocol-service/pom.xml \
  -Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

## Versioning

Uses Maven CI-friendly `${revision}` property. Version is set **once** in `cycles-protocol-service/pom.xml`:
```xml
<revision>0.1.25.1</revision>
```
All child modules inherit via `${revision}` (parent ref) and `${project.version}` (inter-module deps).
The `flatten-maven-plugin` resolves `${revision}` at build time.
