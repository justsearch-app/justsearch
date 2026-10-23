// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from "vitest";
import { render, type TemplateResult } from "lit";
import "./SecuritySurface.js";

interface EncryptionHarness {
  host_: {
    data: { fetch: (path: string, init?: RequestInit) => Promise<Response> };
  };
  encState: string;
  encRecoveryKey: string | null;
  encAwaitingRecoverySave: boolean;
  encError: string | null;
  setupEncryption(passphrase: string): Promise<void>;
  regenerateRecovery(): Promise<void>;
  changePassphrase(oldPass: string, newPass: string): Promise<void>;
  loadEncryption(): Promise<void>;
  renderChatProtection(): TemplateResult;
}

afterEach(() => localStorage.removeItem("enc-recovery-unsaved"));

function harness(response: object): EncryptionHarness {
  const el = document.createElement(
    "jf-security-surface",
  ) as unknown as EncryptionHarness;
  el.encState = "unlocked";
  el.host_ = {
    data: {
      fetch: vi
        .fn()
        .mockResolvedValue({ ok: true, json: async () => response }),
    },
  };
  return el;
}

function notice(el: EncryptionHarness): Element | null {
  const container = document.createElement("div");
  render(el.renderChatProtection(), container);
  return container.querySelector('jf-system-notice[tone="warning"]');
}

it.each(["setup", "recovery", "password"])(
  "shows saved-without-replica warning and retains the returned recovery key (%s)",
  async (action) => {
    const warning =
      "Your change was saved without its recovery replica. The new credentials are in force.";
    const el = harness({
      state: "unlocked",
      saveOutcome: "saved_without_replica",
      recoveryKey: "new-recovery-key",
      warning,
    });
    if (action === "setup") await el.setupEncryption("new-password");
    else if (action === "recovery") await el.regenerateRecovery();
    else await el.changePassphrase("old-password", "new-password");
    expect(notice(el)?.textContent).toContain(warning);
    expect(el.encError).toBeNull();
    if (action !== "password") {
      expect(el.encRecoveryKey).toBe("new-recovery-key");
      expect(el.encAwaitingRecoverySave).toBe(true);
    }
  },
);

it("shows the restored-key startup warning through the existing system notice", async () => {
  const warning = "Your encryption key was restored from its recovery replica.";
  const el = harness({ state: "locked", warning });
  await el.loadEncryption();
  expect(el.encState).toBe("locked");
  expect(notice(el)?.textContent).toContain(warning);
});
