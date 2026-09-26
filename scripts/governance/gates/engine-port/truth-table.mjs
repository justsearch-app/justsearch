/**
 * engine-port truth table (tempdoc 936, lane F design section 3.3).
 *
 * Pure verdict functions, per the discipline-gate kernel contract: every export takes a
 * measurement and returns `{ ruleId, status, reason }`. The enforcer does the measuring — reading
 * the register, scanning source — and dispatches every decision through here, so the gate's
 * judgement is testable without a filesystem.
 */

/**
 * A port declared with `kind: "interface"` must name a file that exists and declares it.
 *
 * @param {{portId: string, interfaceFile: string|undefined, simpleName: string,
 *   fileExists: boolean, declaresInterface: boolean}} m
 */
export function verdictForInterfacePresence(m) {
  if (!m.interfaceFile || !m.fileExists) {
    return {
      ruleId: 'engine-port/missing-interface',
      status: 'fail',
      reason:
        `port '${m.portId}' names interfaceFile ${m.interfaceFile ?? '(none)'}, which does not ` +
        `exist. A catalogue entry pointing at a moved or deleted type is residue that reads as ` +
        `authority.`,
    };
  }
  if (!m.declaresInterface) {
    return {
      ruleId: 'engine-port/missing-interface',
      status: 'fail',
      reason: `port '${m.portId}': ${m.interfaceFile} does not declare 'interface ${m.simpleName}'.`,
    };
  }
  return {
    ruleId: 'engine-port/missing-interface',
    status: 'pass',
    reason: `port '${m.portId}' resolves to ${m.interfaceFile}.`,
  };
}

/**
 * A declared implementation must exist and must still bind its port.
 *
 * <p>Two ways to bind, because both are real here. `via: "implements"` (the default) is a class
 * naming the port in its `implements` clause. `via: "extends <Class>"` is a subclass of a
 * declared implementation — `EngineKnowledgeClient extends KnowledgeClient` is the live binding
 * for both day-one ports, and it never writes `implements SearchPort`. Collapsing the two into
 * one check would have meant either deleting the live binding from the catalogue (leaving the
 * register describing the abstract facade as if it were the thing that runs) or loosening the
 * implements check until it stopped catching anything.
 *
 * @param {{portId: string, className: string, file: string|undefined, simpleName: string,
 *   via: string, fileExists: boolean, bindsPort: boolean}} m
 */
export function verdictForDeclaredImplementation(m) {
  if (!m.file || !m.fileExists) {
    return {
      ruleId: 'engine-port/missing-implementation',
      status: 'fail',
      reason:
        `port '${m.portId}' declares ${m.className} at ${m.file ?? '(no file)'}, which does not ` +
        `exist. Delete the entry with the class, or update it with the rename.`,
    };
  }
  if (!m.bindsPort) {
    const how =
      m.via === 'implements'
        ? `no longer implements ${m.simpleName}`
        : `no longer declares '${m.via}'`;
    return {
      ruleId: 'engine-port/missing-implementation',
      status: 'fail',
      reason:
        `port '${m.portId}' declares ${m.className} as a binding, but ${m.file} ${how}. Delete ` +
        `the entry with the class, or update it with the rename.`,
    };
  }
  return {
    ruleId: 'engine-port/missing-implementation',
    status: 'pass',
    reason: `${m.className} binds ${m.simpleName} via ${m.via}.`,
  };
}

/**
 * The direction that earns the gate its keep: a class found implementing a port must be declared.
 *
 * @param {{portId: string, simpleName: string, path: string, declared: boolean}} m
 */
export function verdictForScannedImplementation(m) {
  if (m.declared) {
    return {
      ruleId: 'engine-port/undeclared-implementation',
      status: 'pass',
      reason: `${m.path} is declared under port '${m.portId}'.`,
    };
  }
  return {
    ruleId: 'engine-port/undeclared-implementation',
    status: 'fail',
    reason:
      `${m.path} implements the '${m.portId}' port (${m.simpleName}) but is not declared in the ` +
      `engine-port register. While the index half was a separate process, implementing the engine ` +
      `API meant writing a gRPC server; in one JVM it means writing 'implements ${m.simpleName}', ` +
      `which any module can do without noticing. Declare the binding under its port, naming the ` +
      `composition-root site that binds it — or bind through the existing one.`,
  };
}

/**
 * Vacuous-pass guard: every check above runs over the scan, so an empty scan passes them all.
 *
 * @param {{found: number, floor: number}} m
 */
export function verdictForScanPopulation(m) {
  if (m.found >= m.floor) {
    return {
      ruleId: 'engine-port/vacuous-scan',
      status: 'info',
      reason: `scan found ${m.found} port implementation(s) (floor ${m.floor}).`,
    };
  }
  return {
    ruleId: 'engine-port/vacuous-scan',
    status: 'fail',
    reason:
      `the scan found ${m.found} port implementation(s), below the declared floor of ${m.floor}. ` +
      `Every completeness check in this gate runs over that scan, so an empty one passes them all ` +
      `over nothing — a gate reporting green because it stopped looking. Either the scan roots ` +
      `moved, or a port genuinely lost a binding, in which case lower the floor in the same ` +
      `change and say why.`,
  };
}

/** @param {{message: string, path: string}} m */
export function verdictForRegisterReadable(m) {
  return {
    ruleId: 'engine-port/register-unreadable',
    status: 'fail',
    reason: `${m.path}: ${m.message}`,
  };
}
