// engine-port gate rule descriptions (tempdoc 936, lane F design section 3.3).

export const ENGINE_PORT_RULE_DESCRIPTIONS = {
  'engine-port/undeclared-implementation': {
    name: 'Undeclared engine-port implementation',
    text:
      'A class implements a registered engine port but is not declared in ' +
      'governance/engine-ports.v1.json. Design 3.3: "Adding a port is a catalogue entry, an ' +
      'interface and a composition-root binding. Nothing else in the repo may construct an ' +
      'implementation of a port." While the index half was a separate process, implementing the ' +
      'engine API meant writing a gRPC server; in one JVM it means writing `implements ' +
      'SearchPort`, which any module can do without noticing. Declare the binding under its port ' +
      '(with the composition-root site that binds it), or bind through the existing one.',
  },
  'engine-port/missing-interface': {
    name: 'Registered port interface not found',
    text:
      'A port declared with kind "interface" names an interfaceFile that does not exist or does ' +
      'not declare that interface. A catalogue entry pointing at a moved or deleted type is ' +
      'residue that reads as authority: the register goes on asserting a port that is not there.',
  },
  'engine-port/missing-implementation': {
    name: 'Declared implementation not found',
    text:
      'A declared implementation names a file that does not exist, or the class in it no longer ' +
      'implements the port. Delete the entry with the class, or update it with the rename.',
  },
  'engine-port/vacuous-scan': {
    name: 'Port implementation scan found too few implementations',
    text:
      'The source scan found fewer implementations than expectedMinImplementations. Every other ' +
      'check in this gate is a completeness check over that scan, so an empty scan makes all of ' +
      'them pass over nothing — a gate reporting green because it stopped looking. Either the ' +
      'scan roots moved (fix them) or a port genuinely lost a binding (lower the floor in the ' +
      'same change, and say why).',
  },
  'engine-port/register-unreadable': {
    name: 'Engine-port register missing or unparseable',
    text: 'governance/engine-ports.v1.json could not be read or parsed.',
  },
};
