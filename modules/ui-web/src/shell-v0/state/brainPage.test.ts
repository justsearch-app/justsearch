// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { composeBrainSentence, type BrainFeature, type BrainFeatureId } from './brainPage.js';
import type { AiEngineVerdict } from './aiVerdict.js';

const verdict = (kind: AiEngineVerdict['kind']): AiEngineVerdict => ({ kind, stability: { kind: 'settled' }, installFailure: null });
function feature(id: BrainFeatureId, on: boolean | null): BrainFeature {
  return { id, label: id, active: on, selected: on === true, installed: true,
    state: 'Text in scans and images comes with the answer model’s files', size: null, switchable: false, blockedReason: null };
}
function features(reranker: boolean | null, chat: boolean | null, pictures: boolean | null) {
  return { meaning: feature('meaning', true), reranker: feature('reranker', reranker), chat: feature('chat', chat), pictures: feature('pictures', pictures) };
}
const combinations = [true, false, null].flatMap(reranker => [true, false, null].flatMap(chat => [true, false, null].map(pictures => ({ reranker, chat, pictures }))));
describe('Brain sentence from observed state and saved choice', () => {
  it.each(combinations)('installed: reranker=$reranker answers=$chat pictures=$pictures', ({ reranker, chat, pictures }) => {
    const parts = composeBrainSentence(verdict('online'), true, features(reranker, chat, pictures), false);
    const phrase = (id: BrainFeatureId) => parts.find(part => part.feature === id)?.text;
    expect(phrase('meaning')).toBe('It understands what your files are about');
    expect(phrase('reranker')).toBe(reranker === true ? ', puts the best match first' : reranker === false ? ', without best match first' : ', with best match first unconfirmed');
    expect(phrase('chat')).toBe(chat === true ? ' and answers your questions with sources' : '. Answers are off; background work can continue');
    expect(phrase('pictures')).toBe(pictures === true ? '. It reads text in scans and images' : pictures === false ? ". It doesn't read text in scans and images yet" : '. Reading text in scans and images is unconfirmed');
    expect(parts.at(-1)?.text).toBe('. Everything runs on this computer.');
  });
  it.each([true, false].flatMap(reranker => [true, false].map(chat => ({ reranker, chat }))))('before install: reranker=$reranker answers=$chat', ({ reranker, chat }) => {
    const parts = composeBrainSentence(verdict('not_installed'), false, features(reranker, chat, false), false);
    expect(parts[0]?.text).toContain('Installing AI lets it understand');
    expect(parts.find(part => part.feature === 'reranker')?.text).toBe(reranker ? ', put the best match first' : ', without best match first');
    expect(parts.find(part => part.feature === 'chat')?.text).toBe(chat ? ' and answer your questions with sources' : ', without answers to questions');
    expect(parts.find(part => part.feature === 'pictures')?.text).toContain('comes with the answer model’s files');
  });
  it.each(['unavailable', 'not-in-mode', 'declined', 'selected'] as const)('before install never promises excluded answers: %s', state => {
    const f = features(true, true, false);
    f.chat.availability = state === 'unavailable' || state === 'not-in-mode' ? 'unavailable' : 'available';
    f.chat.selected = state !== 'declined';
    const text = composeBrainSentence(verdict('not_installed'), false, f, false).find(part => part.feature === 'chat')?.text;
    expect(text).toBe(state === 'unavailable' || state === 'not-in-mode' ? ', with answers unavailable on this computer' : state === 'declined' ? ', without answers to questions' : ' and answer your questions with sources');
  });
  it('unknown status never claims availability or a missing installation', () => {
    const text = composeBrainSentence(verdict('connecting'), false, features(null, null, null), false).map(part => part.text).join('');
    expect(text).toBe('AI status and feature availability are still being checked. Everything runs on this computer.');
  });
  it('says picture reading is on but not available with this answer model when the feature state does', () => {
    const state = features(true, true, false);
    state.pictures.state = 'On — not available with this answer model';
    const parts = composeBrainSentence(verdict('online'), true, state, false);
    expect(parts.find(part => part.feature === 'pictures')?.text).toBe('. Text in scans and images: On — not available with this answer model');
  });
  it('answers-off takes precedence over a picture-reading hold', () => {
    const parts = composeBrainSentence(verdict('background'), true, features(true, false, true), true);
    expect(parts.find(part => part.feature === 'chat')?.text).toBe('. Answers are off; background work can continue');
  });
  it('answers resume after picture reading and missing answers never claim to answer', () => {
    const state = features(true, true, true);
    expect(composeBrainSentence(verdict('online'), true, state, true).find(part => part.feature === 'chat')?.text).toContain('resume after the current document');
    expect(composeBrainSentence(verdict('online'), true, state, false).find(part => part.feature === 'chat')?.text).toContain('answers your questions with sources');
    state.chat.installed = false;
    expect(composeBrainSentence(verdict('online'), true, state, false).find(part => part.feature === 'chat')?.text).toBe('. Answers are not downloaded yet');
  });
});
