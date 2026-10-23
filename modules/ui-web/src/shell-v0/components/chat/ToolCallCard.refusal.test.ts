// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { AgentSessionController } from '../../controllers/AgentSessionController.js';
import type { ToolCallCard } from './ToolCallCard.js';
import './ToolCallCard.js';

// Java-produced refusal payload, with its operation identifier replaced by a fixed test UUID.
const completion = {
  callId: 'call', success: false,
  output: 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)',
  executionId: '', errorCode: 'BULK_GENERATION_REFUSED', retryable: false,
  structuredData: { operationKey: '00000000-0000-7000-8000-000000000001' },
};
let server: ReturnType<typeof createServer> | undefined;
let controller: AgentSessionController | undefined;

afterEach(async () => {
  controller?.destroy();
  document.body.replaceChildren();
  if (server) {
    server.closeAllConnections();
    await new Promise<void>(resolve => server!.close(() => resolve()));
  }
});

it.each(['flat', 'nested'] as const)('shows the sentence and support code from a %s tool completion stream', async shape => {
  const event = (name: string, payload: object) => `event: ${name}\ndata: ${JSON.stringify(payload)}\n\n`;
  const requests: string[] = [];
  server = createServer((req, res) => {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*',
        'Access-Control-Allow-Methods': 'POST,OPTIONS' });
      res.end(); return;
    }
    requests.push(`${req.method} ${req.url}`);
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Access-Control-Allow-Origin': '*' });
    res.end(event('run_started', { runId: 'refused-run', shapeId: 'core.agent-run' })
      + event('tool_call_proposed', { callId: completion.callId, toolName: 'core_bulk_reindex',
        arguments: '{}', risk: 'LOW', gateBehavior: 'auto' })
      + event('tool_exec_started', { callId: completion.callId })
      + event('tool_exec_completed', shape === 'flat' ? completion : { callId: completion.callId, result: completion })
      + event('done', { iterationsUsed: 1, finalResponse: '' }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const card = document.createElement('jf-tool-call-card') as ToolCallCard;
  controller = new AgentSessionController(base, () => {
    card.toolCall = controller!.toolCalls[completion.callId] ?? null;
  });
  document.body.append(card);
  await controller.attachToRun('refused-run');
  await card.updateComplete;
  await card.updateComplete; // failed calls stay expanded after the completion update
  expect(requests).toEqual(['POST /api/chat/runs/refused-run/observe']);
  expect(card.toolCall?.status).toBe('completed');
  expect(card.expanded).toBe(true);
  expect(card.shadowRoot!.querySelector('.tool-output')?.textContent).toBe(completion.output);
  expect(card.shadowRoot!.textContent).toContain(`(Code: ${completion.errorCode})`);
  expect(card.shadowRoot!.textContent?.split(`Code: ${completion.errorCode}`)).toHaveLength(2);
});
