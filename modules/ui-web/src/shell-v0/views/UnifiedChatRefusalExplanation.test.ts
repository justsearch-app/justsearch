// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { render } from 'lit';
import type { TemplateResult } from 'lit';
import './UnifiedChatView.js';
import type { UnifiedChatView } from './UnifiedChatView.js';
import type { ToolCallCard } from '../components/chat/ToolCallCard.js';
import { AgentSessionController } from '../controllers/AgentSessionController.js';
import { projectLiveAgentActivity, projectUnifiedThread, type UnifiedTurnItem } from './unifiedThreadProjection.js';

const output = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)';
let controller: AgentSessionController | undefined;
afterEach(() => { controller?.destroy(); document.body.replaceChildren(); });

// The Java producer/record-mapper tests pin this message at the source. These checks preserve
// the full chat's existing live and reloaded rendering without introducing another code field.
it.each(['live', 'reloaded'] as const)('full chat renders a self-contained %s refusal exactly once', async mode => {
  let item: UnifiedTurnItem;
  if (mode === 'live') {
    controller = new AgentSessionController('', () => {});
    controller.onToolCallProposed({ callId: 'call', toolName: 'core_bulk_reindex', arguments: '{}', risk: 'LOW' });
    controller.onToolExecStarted({ callId: 'call', toolName: 'core_bulk_reindex' });
    controller.onToolExecCompleted({ callId: 'call', success: false, output, errorCode: 'BULK_GENERATION_REFUSED' });
    item = projectLiveAgentActivity(controller.conversation, controller.toolCalls)[0]!;
  } else {
    item = projectUnifiedThread([{ id: 'call:completed', occurredAt: '2026-10-22T17:00:00Z',
      kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'call',
        toolName: 'core_bulk_reindex', status: 'completed', success: false, output } }])[0]!;
  }
  const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
  const container = document.createElement('div'); document.body.append(container);
  render((view as unknown as { renderToolActivity(item: UnifiedTurnItem): TemplateResult }).renderToolActivity(item), container);
  const card = container.querySelector('jf-tool-call-card') as ToolCallCard;
  expect(card).not.toBeNull(); await card.updateComplete; await card.updateComplete;
  expect(card.expanded).toBe(true);
  expect(card.shadowRoot!.querySelector('.tool-output')?.textContent).toBe(output);
  expect(card.shadowRoot!.textContent?.split('Code: BULK_GENERATION_REFUSED')).toHaveLength(2);
});
