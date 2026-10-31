# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.3.x | Yes |
| Older/unreleased versions | No |

## Reporting a Vulnerability

If you discover a security vulnerability in JustSearch, please report it privately.
**Do not disclose it as a public issue.**

Use GitHub's [private vulnerability reporting](https://github.com/justsearch-app/justsearch/security/advisories/new) to submit a security advisory.

### What to expect

- **Acknowledgment** within 48 hours of report.
- **Assessment** with severity classification within 7 days.
- **Fix timeline** based on severity:
  - Critical/High: patch within 14 days.
  - Medium: patch within 30 days.
  - Low: addressed in next regular release.

### Scope

JustSearch is a local-first desktop application. The local API and MCP server bind to loopback (`127.0.0.1`); the app's outbound network paths are listed in [README's Privacy section](README.md#privacy). The following are in scope:

- Local privilege escalation via the application.
- Data exposure through the local API (bound to loopback).
- Malicious model/file handling during indexing or inference.
- The local **MCP server** (`POST /mcp`) tool surface, through which external AI agents can drive search, retrieval, folder browse, and **ingest**.
- The agent's **gated actions** (file operations, folder browse, ingestion) and the consent / session-token gating that protects them.
- Supply chain issues in dependencies.

For the full STRIDE analysis of these surfaces — loopback hardening, session-token gating on `POST /mcp` mutations, and the least-privilege tool surface — see [docs/reference/security/threat-model.md](docs/reference/security/threat-model.md).

The following are **not** considered vulnerabilities:

- Issues requiring physical access to the machine beyond normal user access.
- Denial of service against the local application (user can restart it).
- Issues in third-party AI models or runtimes (report to upstream).
