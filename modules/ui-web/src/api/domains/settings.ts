// SPDX-License-Identifier: Apache-2.0
/**
 * Settings domain API - User preferences and configuration
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
import { settingsV2Schema } from '../generated/schema-types/settings-v2';
import { createSettingsAttempt, executeSettingsAttempt, type SettingsWitness, type SettingsCompletion, type SettingsPatch } from '../settingsAttempt.js';
import { authorizedFetch } from '../../shell-v0/api/authorizedFetch.js';

// ============================================
// Types
// ============================================

export interface UISettings {
  theme?: 'system' | 'dark' | 'light' | undefined;
  highContrast?: boolean | undefined;
  density?: 'compact' | 'comfort' | 'rich' | undefined;
  defaultAction?: 'open' | 'reveal' | 'preview' | undefined;
  inspectorWidth?: number | undefined;
  // Power-user AI behavior (client-side only; server may ignore)
  pauseIndexingDuringAi?: boolean | undefined;
  // Progressive disclosure mode (default: simple)
  mode?: 'simple' | 'advanced' | undefined;
  // One-time trust-loop teaching moment (citations)
  hasSeenTrustLoopNudge?: boolean | undefined;
  // Glob patterns to exclude from indexing/search (applied via explicit action)
  excludePatterns?: string[] | undefined;
  // Vim-style keyboard navigation
  vimMode?: boolean | undefined;
}

export interface LLMSettings {
  // Desktop/Tauri only: optional BYO llama-server executable path override
  serverExecutable?: string | undefined;
  contextWindow?: number | undefined;
  maxTokens?: number | undefined;
  gpuLayers?: number | undefined;
  modelPath?: string | undefined;
  llamaLibPath?: string | undefined;
}

export interface AppSettings {
  ui?: UISettings | undefined;
  llm?: LLMSettings | undefined;
  indexPaths?: string[] | undefined;
  settingsMode?: 'read_write' | 'in_memory' | undefined;
  witness?: SettingsWitness;
}

// ============================================
// API Functions
// ============================================

/**
 * Fetches settings (canonical v2 endpoint), validated against the generated
 * `settingsV2Schema` at the parse boundary (tempdoc 683).
 */
export async function getSettingsV2(
  baseUrl: string,
  signal?: AbortSignal
): Promise<AppSettings> {
  const raw = await request<unknown>(baseUrl, '/api/settings/v2', { signal });
  // The AppSettings view narrows the wire's plain strings to the FE literal unions —
  // the same narrowing the previous unchecked `request<AppSettings>` cast performed,
  // now behind a validated wire shape.
  return parseWireContract(settingsV2Schema, raw, 'GET /api/settings/v2') as AppSettings;
}

/** Updates a patch against the witness observed with its base; replay may return only a receipt. */
export async function updateSettingsV2(
  baseUrl: string,
  settings: SettingsPatch,
  witness: SettingsWitness,
  signal?: AbortSignal,
): Promise<SettingsCompletion> {
  const attempt = createSettingsAttempt(settings, witness);
  return executeSettingsAttempt((path, init) => authorizedFetch(baseUrl + path, init), attempt, { signal });
}
