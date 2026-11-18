---
schema: test-intent.v1
task: t-20261118-update-whatsnew
author-role: builder
author-session: aa8a5b26-b2e1-4a72-89d7-af83449a03db
---

# Test-intent entries

Settings > App updates now offers "What's new in X" beside "Install X". It opens the GitHub release
page of tag vX through openExternalUrl. Only a strict SemVer available version gets the link.
`SettingsSurface.appUpdate.test.ts` is an existing file. Its two existing checks keep their oracle
under a shared render helper (Adaptation). The added checks pin the link (C1), its absence for
non-strict-SemVer versions (C2) and the unchanged update controls (C3) (New).

The check-id syntax rewrites a backslash to `/`, so the case
`omits release notes for an invalid version "0.3.7\n"` cannot be named alone. The whole-file check
`modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts` in
`update-whatsnew-link-guards.json` records it as executed and passed with the other 18 cases.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The two existing checks ('shows an authenticated available release and requires an explicit install action', 'invokes installation only after the user activates the install control') move their duplicated mount code into renderAppUpdates(). The helper does the same steps: mount jf-settings-surface with the same mock host and native-notifications capability, wait a tick, set activeCategory 'general', await updateComplete and return the [data-testid=\"settings-app-updates\"] section. It throws if the section is absent, where the old code failed on the optional-chained assertion. The status fixture keeps the same values (available, 1.0.0 -> 1.1.0, releaseSequence 4). It moves into a per-test variable, and the invoke mock now also returns it for check_for_app_update. The existing checks never call that command. The install control is now looked up inside the App updates section instead of the whole shadow root, which is narrower. onActivate() becomes onActivate?.(), and a missing handler is still caught because the check asserts that install_app_update was invoked. The inputs, explicit-install precondition, observed text and invoke calls, and expected outcomes are unchanged. Only a stale code comment (tempdoc 855 P1) is shortened."
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts"],
      "class": "New",
      "behaviour": "C1: when an update to a strict SemVer version is available (0.3.7, or prerelease 0.3.7-rc.1), the App updates section shows a secondary or ghost button named \"What's new in <version>\" beside \"Install <version>\" (same parent). Activating it opens https://github.com/justsearch-app/justsearch/releases/tag/v<version> through openExternalUrl exactly once. Nothing opens before activation, and activation does not install.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with release and your identified further work",
          "delegationLocation": "task t-20261118-update-whatsnew request (2026-11-18 user turn)",
          "delegationScope": "The identified further work, as stated to the owner in the preceding turn: \"The in-app update prompt shows no release notes. Users updating from inside the app see no \"what's new\".\"",
          "choice": "Show a secondary \"What's new in <version>\" button beside the Install button of the in-app update offer. It opens the GitHub release page of the offered version's tag in the external browser only when activated, and it never installs.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "location": "docs/how-to/cut-a-release.md:84-85",
          "quote": "export REPO=justsearch-app/justsearch export TAG=\"v$VERSION\""
        },
        {
          "kind": "owner-words",
          "location": "docs/how-to/cut-a-release.md:329-330",
          "quote": "Give the owner one page: version, what changed, journey results, what was not tested and known issues."
        },
        {
          "kind": "owner-words",
          "location": "docs/how-to/cut-a-release.md:337",
          "quote": "Update the draft's notes from that summary, filling `Not tested` and `Known issues`;"
        },
        {
          "kind": "owner-words",
          "location": "docs/reference/configuration/environment-variables.md:262-263",
          "quote": "Installation rechecks `latest.json` and downloads installer bytes only on request."
        }
      ],
      "evidence": "gates/test-intent/evidence/update-whatsnew-link.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#opens the release notes for 0.3.7 on activation",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#opens the release notes for 0.3.7-rc.1 on activation"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts"],
      "class": "New",
      "behaviour": "C2: an available version that is not strict SemVer gets no \"What's new\" control. Covered values: path-like ('../x', '0.3.7/../../x'), whitespace or newline ('0.3.7 evil', '0.3.7\\n'), empty, a 'v' prefix, build metadata ('0.3.7+build'), a leading zero ('00.3.7', '0.3.7-rc.01') and malformed prereleases ('0.3.7-', '0.3.7-rc..1'). The URL is built only from a version that maps to a release tag v<X.Y.Z[-prerelease]>. C3 (preservation): with state up_to_date, checking or repair_required, no \"What's new\" control and no Install update control render. Check for updates is enabled except under repair_required. Activating Check for updates invokes check_for_app_update, which it does not invoke before activation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with release and your identified further work",
          "delegationLocation": "task t-20261118-update-whatsnew request (2026-11-18 user turn)",
          "delegationScope": "The identified further work, as stated to the owner in the preceding turn: \"The in-app update prompt shows no release notes. Users updating from inside the app see no \"what's new\".\"",
          "choice": "Build the release-notes URL only from a strict SemVer version without build metadata, so a malformed or hostile updater version never becomes an outbound URL path. Show the link only with an install offer, and leave the existing Check for updates and Install controls in every other state as they are.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "location": "docs/how-to/cut-a-release.md:85",
          "quote": "export TAG=\"v$VERSION\""
        },
        {
          "kind": "owner-words",
          "location": "docs/how-to/cut-a-release.md:193-194",
          "quote": "higher than the installed predecessor (the updater uses SemVer ordering)."
        },
        {
          "kind": "owner-words",
          "location": "docs/reference/configuration/environment-variables.md:262-263",
          "quote": "Installation rechecks `latest.json` and downloads installer bytes only on request."
        },
        {
          "kind": "owner-words",
          "location": "docs/reference/configuration/environment-variables.md:282",
          "quote": "Settings and recovery manual checks still work when policy allows them."
        }
      ],
      "evidence": "gates/test-intent/evidence/update-whatsnew-link-guards.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"../x\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7 evil\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7/../../x\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"v0.3.7\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7+build\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"00.3.7\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7-\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7-rc..1\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes for an invalid version \"0.3.7-rc.01\"",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes when up_to_date",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes when checking",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#omits release notes when repair_required",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.appUpdate.test.ts#checks for updates when the user activates the check control"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "67c18bcc-72e6-476d-9d53-f7be8b5ba9c7",
  "verdict": "accept",
  "digest": "sha256:2a92dade5ebe8add595dfc2c4d4a7b21d306b1a9e820a1de56a677350821cbd9",
  "note": "Adaptation keeps both old oracles (pass on f04e9c4ced and candidate); C1 checks fail-before by assertion, C2/C3 pass on both; expectations follow the delegated choice; reran on both trees."
}
```
