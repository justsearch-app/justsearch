import { CONFIG_LIFECYCLE_RULE_DESCRIPTIONS } from './lifecycle.mjs';

export const CONFIG_SURFACE_RULE_DESCRIPTIONS = {
  ...CONFIG_LIFECYCLE_RULE_DESCRIPTIONS,
  'config-surface/within-baseline': 'Configuration-surface metric is at or below baseline',
  'config-surface/silent-growth':
    'The runtime configuration surface grew without a declared changeset. Every knob is a decision deferred to runtime; 754 showed a one-shot cleanup without regrowth pressure just gets re-paid. Declare the growth or delete a knob.',
  'config-surface/declared-growth':
    'Configuration surface grew; classification covers it',
  'config-surface/merge-import': 'Configuration-surface growth via merge; classification supplied',
  'config-surface/emergency-override':
    'Configuration-surface growth permitted via emergency-override',
  'config-surface/rebalance-available':
    'Configuration surface shrank below baseline; ratchet may be rebalanced',
  'config-surface/silent-baseline-shift':
    'Configuration-surface baseline relaxed in this PR without a declared changeset',
  'config-surface/dead-key':
    'A setting is declared but nothing reads it — not resolved into ResolvedConfig, not read via its EnvRegistry constant, and its key string appears nowhere outside the configuration module. Wire it or delete the declaration.',
  'config-surface/dead-key-baselined':
    'A known dead setting, recorded in the dead-config baseline (not blessed — shrinking that list is the point)',
  'config-surface/sysaccess-allowlist-growth':
    'gates/config-surface/sysaccess-allowlist.txt gained an entry. That file is a ratchet over direct System.getenv/getProperty/setProperty use outside io.justsearch.configuration and only shrinks; adding a line is how SystemAccessFunnelTest gets made green without routing the value through the resolver. Declare the growth or migrate the site.',
  'config-surface/yaml-key-unread':
    'A key in the shipped config/application.yaml that nothing reads — no putYaml* contribution, no JsonNode walk, no consumer naming it. The mirror of dead-key: it exists where the USER looks and nowhere the CODE looks. Wire it, or delete it from the YAML.',
  'config-surface/yaml-key-unread-baselined':
    'A known unread YAML key, recorded in the dead-config baseline (not blessed — shrinking that list is the point)',
  'config-surface/yaml-parse-failed':
    'config/application.yaml could not be parsed — the yaml-reader scan cannot run, so a dead key would go unseen',
  'config-surface/unread-component':
    'A ResolvedConfig record component whose accessor is never called in production code',
  'config-surface/unread-component-baselined':
    'A known unread component, recorded in the dead-config baseline',
  'config-surface/report-malformed':
    'The runtime-config matrix report could not be parsed or lacks a nonnegative integer metric',
  'config-surface/baseline-malformed':
    'A required configuration count baseline pin is missing or is not a nonnegative integer',
};
