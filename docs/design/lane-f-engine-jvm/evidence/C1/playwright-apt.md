# Hosted Playwright dependency source preparation

CI34383091150 at0b4b13ad0 fails browser installation twice with identical Google Chrome
APT Packages.gz hash disagreement. CI34386342721 at41c575f21 repeats it. These jobs never
reach measured axe, so none supplies accessibility proof. Raw failures remain available:
`tmp/c1-hosted-failure-394.txt`, `tmp/c1-hosted-failure-retry-398.txt`,
`tmp/c1-hosted-browser-failure-407.txt`.

## Decision and scope

The section0 decision authorizes a dedicated preparation step immediately before the existing
`python -m playwright install --with-deps chromium`. The job uses Playwright Chromium rather
than the branded Chrome channel, which Playwright documents as a separate distribution:
[Playwright browser documentation](https://playwright.dev/docs/browsers#chromium).
Ubuntu supports `.list` and deb822 `.sources` files under its source directory:
[Ubuntu third-party source documentation](https://ubuntu.com/server/docs/explanation/software/third-party-repository-usage/).

The helper identifies only exact dl.google.com Google Chrome repository URIs. It validates
all candidates first, rejects mixed/unknown source files, duplicate URI fields and existing
backup collisions, then renames dedicated files to `.disabled`. Their bytes remain available
for inspection. Other repositories and every APT signature/hash check stay in force. No
stale-index fallback, ignored install failure or axe suppression is introduced. The current
job does not install/update branded Google Chrome; future such work must own its repository.

Root compared simpler fixed-filename deletion with content validation: retained logs do not
name a source file and Ubuntu supports two formats. A dedicated helper with filesystem fixtures
keeps the exact exclusion bounded and reviewable. This is ephemeral hosted-runner setup, not
an installed-product policy or a new persistent source authority.

## Local verification

Windows11 / Node24.12, based5b60ac3c plus this item. Tests412 pass8 cases; root then identified
duplicate deb822 URI fields as an ambiguity and added fail-closed handling. Final416 passes9
cases, including actual refusal before any rename. Dedicated-file bytes and unrelated source
bytes are verified; repeated preparation is a no-op. Lint has zero warnings. Build414 passes
in4s; workflow trigger/npm policy and canonical links415 pass. The final helper-only refinement
changes no Gradle inputs; its current source is covered by416.

Raw: `tmp/c1-playwright-apt-tests-412.txt`, `tmp/c1-playwright-apt-lint-413.txt`,
`tmp/c1-playwright-apt-build-414.txt`, `tmp/c1-playwright-apt-workflow-415.txt`,
`tmp/c1-playwright-apt-final-416.txt`.

Hosted execution remains required: preparation must identify the actual source, dependency
installation must succeed with ordinary integrity validation, and all measured axe captures
must run and upload. A local helper pass does not satisfy that platform obligation.
