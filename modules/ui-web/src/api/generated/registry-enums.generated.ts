/* eslint-disable */
// GENERATED — do not edit by hand.
// Single authority: the Java enums in io.justsearch.agent.api.registry / io.justsearch.agent.api.conversation (see RegistryEnumsTsGenerationTest.sharedEnums());
// io.justsearch.agent.api.interaction.InteractionEventKind owns INTERACTION_EVENT_KINDS.
// Regenerate: ./gradlew :modules:app-agent-api:test -PupdateSchemas --tests '*RegistryEnumsTsGenerationTest'
// Drift-checked by RegistryEnumsTsGenerationTest (tempdoc 560 §4.1/§4.3 anti-drift).

export type TrustTier = 'CORE' | 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN';
export type Audience = 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';
export type RiskTier = 'LOW' | 'MEDIUM' | 'HIGH';
export type Altitude = 'PRODUCT' | 'DIAGNOSTIC' | 'TRUST' | 'TOOL';
export type ExecutorTag = 'UI' | 'AGENT' | 'CLI';
export type SourceTier = 'TRUSTED' | 'MEDIUM' | 'UNTRUSTED';
export type GateBehavior = 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY';
export type SubscriptionMode = 'ONE_SHOT' | 'SSE_STREAM' | 'POLLING';
export type PathPolicy = 'NO_PATHS' | 'RAW' | 'HASHED_REQUIRES_RESOLVER';
export type OnOverflow = 'EVICT_OLDEST' | 'BACKPRESSURE' | 'DROP_NEWEST';
export type AuditPolicy = 'NONE' | 'METADATA_ONLY';
export type RenderHint = 'EPHEMERAL' | 'PERSISTED' | 'REQUIRES_ACK';
export type HistoryMode = 'RING_BUFFER' | 'DURABLE' | 'EXTERNAL';
export type IterationMode = 'ONE_SHOT' | 'WITHIN_TURN_ITERATION';
export type PersistenceMode = 'EPHEMERAL' | 'PERSISTENT';
export type ExecutionMode = 'SUBSTRATE_DRIVEN' | 'SHAPE_DRIVEN';

export const INTERACTION_EVENT_KINDS = [
  'USER_MESSAGE',
  'ASSISTANT_MESSAGE',
  'TOOL_ACTIVITY',
  'PROGRESS',
  'ERROR',
  'HANDOFF',
  'SEARCH',
] as const;
export type InteractionEventKind = (typeof INTERACTION_EVENT_KINDS)[number];
