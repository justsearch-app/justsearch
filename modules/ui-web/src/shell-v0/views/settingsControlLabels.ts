// SPDX-License-Identifier: Apache-2.0
/** Visible control text shared by the Settings register and the controls that render it. */
export const SETTINGS_CONTROL_LABELS = {
  "desktop": {
    "startup": "Launch on startup",
    "shortcut": "Global search shortcut"
  },
  "interface": {
    "mode": "Mode",
    "simple": "Simple",
    "detailed": "Detailed",
    "theme": "Theme variant",
    "system": "System",
    "dark": "Dark",
    "light": "Light",
    "solid": "Solid surfaces"
  },
  "accessibility": {
    "density": "Density",
    "compact": "Compact",
    "comfortable": "Comfortable",
    "spacious": "Spacious",
    "contrast": "High contrast",
    "motion": "Reduce motion"
  },
  "chat-width": {
    "label": "Chat width",
    "narrow": "Narrow",
    "default": "Default",
    "wide": "Wide"
  },
  "notifications": {
    "label": "Desktop notifications",
    "off": "Off",
    "on": "On",
    "sound": "On with sound"
  },
  "view-tier": {
    "label": "View tier preference",
    "user": "User",
    "operator": "Operator",
    "developer": "Developer"
  },
  "keyboard": {
    "action": "Enter action"
  },
  "feedback-capture": {
    "label": "Improve ranking from your activity (local only)"
  },
  "token-editor": {
    "label": "Custom theme tokens",
    "open": "Open Token Editor"
  },
  "rail": {
    "up": "Move up",
    "down": "Move down",
    "reset": "Reset"
  },
  "plugins": {
    "url": "Plugin URL (e.g., http://localhost:3000/plugin.js)",
    "load": "Load",
    "revoke": "Revoke"
  },
  "durable-grants": {
    "revoke": "Revoke",
    "family": "Capability family (e.g. file-operations)",
    "grant": "Grant family"
  },
  "plugin-permissions": {
    "allow": "allow-always",
    "deny": "deny",
    "revoke": "Revoke"
  },
  "workspace-profiles": {
    "parent": "— No parent (flat profile) —",
    "snapshot": "Snapshot current as Profile",
    "activate": "— Activate a profile —"
  },
  "data": {
    "delete": "Delete all local data"
  },
  "app-updates": {
    "check": "Check for updates",
    "install": "Install update"
  },
  "update-information": {
    "open": "Open Updates"
  },
  "theme": {
    "default": "Default tokens",
    "save": "Save new name",
    "cancelRename": "Cancel rename",
    "import": "Import a theme from JSON",
    "paste": "Paste a theme's exported JSON",
    "importPasted": "Import the pasted theme",
    "cancelImport": "Cancel import"
  },
  "indexing": {
    "change": "Change when to index"
  },
  "context-window": {
    "open": "Reading memory (context window)"
  },
  "core.security-surface": {
    "saved": "I've saved my recovery key",
    "choose": "Choose a passphrase",
    "encrypt": "Encrypt chat history",
    "passphrase": "Passphrase",
    "unlock": "Unlock",
    "regenerate": "Regenerate recovery key",
    "changePassphrase": "Change passphrase",
    "export": "Export encrypted backup",
    "import": "Import backup",
    "lock": "Lock now",
    "backupPassphrase": "Backup's passphrase",
    "restore": "Restore",
    "autoLock": "Auto-lock after inactivity",
    "off": "Off",
    "five": "5 minutes",
    "fifteen": "15 minutes",
    "thirty": "30 minutes",
    "currentPassphrase": "Current passphrase",
    "newPassphrase": "New passphrase",
    "change": "Change"
  },
  "core.presentation-gallery-surface": {
    "revert": "Revert",
    "export": "Export active",
    "applied": "Applied ✓",
    "apply": "Apply",
    "paste": "Import a skin (paste inert JSON)",
    "import": "Import + certify"
  },
  "core.presentation-editor-surface": {
    "fork": "Fork active surface",
    "apply": "Certify & apply",
    "json": "Presentation declaration JSON",
    "describe": "Describe a surface",
    "prompt": "Describe a look… e.g. warm dark theme",
    "generate": "Generate from prompt",
    "generateLocal": "Generate (on-device)"
  },
  "agent-autonomy": {
    "watch": "Watch",
    "assist": "Assist",
    "auto": "Auto"
  }
} as const;
