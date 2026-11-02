// SPDX-License-Identifier: Apache-2.0
import type { AiEngineVerdict } from './aiVerdict.js';

export type BrainFeatureId = 'meaning' | 'reranker' | 'chat' | 'pictures';
export interface BrainFeature {
  id: BrainFeatureId;
  label: string;
  /** Observed availability, distinct from the saved acquisition/runtime choice. */
  active: boolean | null;
  availability?: 'available' | 'unavailable' | 'unknown';
  selected: boolean;
  installed: boolean;
  state: string;
  size: string | null;
  switchable: boolean;
  blockedReason: string | null;
}

export interface BrainSentencePart {
  text: string;
  feature?: BrainFeatureId;
}

/** One sentence authority for the two presentations of each feature's shared state. */
export function composeBrainSentence(
  verdict: AiEngineVerdict,
  installed: boolean,
  features: Readonly<Record<BrainFeatureId, BrainFeature>>,
  readingPictures: boolean | null,
): BrainSentencePart[] {
  const { meaning, reranker, chat, pictures } = features;
  const parts: BrainSentencePart[] = [];
  if (!installed && verdict.kind === 'connecting') {
    return [{ text: 'AI status and feature availability are still being checked. Everything runs on this computer.' }];
  }
  if (!installed) {
    parts.push({ text: 'Search already works with exact words. Installing AI lets it understand what your files are about', feature: 'meaning' });
    parts.push({ text: reranker.availability === 'unavailable' ? ', with best match first unavailable on this computer' : reranker.selected ? ', put the best match first' : ', without best match first', feature: 'reranker' });
    parts.push({ text: chat.availability === 'unavailable' ? ', with answers unavailable on this computer' : chat.selected ? ' and answer your questions with sources' : ', without answers to questions', feature: 'chat' });
    parts.push({ text: `. ${pictures.state}`, feature: 'pictures' });
  } else {
    parts.push({ text: meaning.active === true ? 'It understands what your files are about' : meaning.active === false ? 'Understanding meaning is not active' : 'Understanding meaning is unconfirmed', feature: 'meaning' });
    parts.push({ text: reranker.active === true ? ', puts the best match first' : reranker.active === false ? ', without best match first' : ', with best match first unconfirmed', feature: 'reranker' });
    parts.push({ text: !chat.installed ? '. Answers are not downloaded yet' : !chat.selected ? '. Answers are off; background work can continue' : readingPictures === null ? '. Pictures were being read when last observed; current answer availability is unconfirmed' : readingPictures ? '. Answers are temporarily unavailable while pictures are being read; they resume after the current document' : chat.active === true ? ' and answers your questions with sources' : chat.active === null ? '. Answer availability is unconfirmed' : '. Answers are not available yet', feature: 'chat' });
    parts.push({ text: pictures.active === true ? '. It reads text in scans and images' : pictures.active === false ? ". It doesn't read text in scans and images yet" : '. Reading text in scans and images is unconfirmed', feature: 'pictures' });
  }
  if (verdict.kind === 'connecting') parts.push({ text: '. AI status is still being checked' });
  parts.push({ text: '. Everything runs on this computer.' });
  return parts;
}
