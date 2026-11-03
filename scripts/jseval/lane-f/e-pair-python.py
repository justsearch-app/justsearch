"""Inventory repository Python import dependencies without importing/running them.

Includes function-local imports conservatively: those are instrument dependencies,
not a sweep of unrelated scripts. External libraries remain machine/environment pins.
"""
import ast
import json
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
roots = {'jseval': root, 'jsharness': root.parent / 'jsharness'}
pending = [Path(p).resolve() for p in sys.argv[2:]]
seen = set()


def add_module(module):
    parts = module.split('.')
    package_root = roots.get(parts[0])
    if package_root is None:
        return
    base = package_root.joinpath(*parts)
    for parent in [package_root / parts[0], *base.parents]:
        if parent == package_root or package_root not in parent.parents:
            continue
        init = parent / '__init__.py'
        if init.is_file():
            pending.append(init)
    for candidate in [base.with_suffix('.py'), base / '__init__.py']:
        if candidate.is_file():
            pending.append(candidate)


while pending:
    file = pending.pop()
    if file in seen:
        continue
    seen.add(file)
    tree = ast.parse(file.read_text(encoding='utf-8-sig'), filename=str(file))
    package_root = next((p for p in roots.values() if p in file.parents), None)
    package = '.'.join(file.parent.relative_to(package_root).parts) if package_root else ''
    # CLI boot imports every registered command. Derive its dynamic import list
    # from the owning registry, rather than copying a second command catalog.
    if file == root / 'jseval' / 'cli.py':
        registry = ast.parse((root / 'jseval' / 'commands' / '__init__.py').read_text(encoding='utf-8-sig'))
        for assignment in registry.body:
            if isinstance(assignment, ast.Assign):
                names = {target.id for target in assignment.targets if isinstance(target, ast.Name)}
                for name, prefix in [('_GROUP_MODULES', 'jseval.commands.'), ('_LEGACY_MODULES', 'jseval.')]:
                    if name in names:
                        for module in ast.literal_eval(assignment.value):
                            add_module(prefix + module)
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                add_module(alias.name)
        if isinstance(node, ast.ImportFrom):
            if node.level:
                parts = package.split('.')
                module = '.'.join(parts[:len(parts) - node.level + 1] + ([node.module] if node.module else []))
            else:
                module = node.module or ''
            if module.split('.')[0] in roots:
                add_module(module)
                for alias in node.names:
                    add_module(f'{module}.{alias.name}')

print(json.dumps(sorted(str(p) for p in seen)))
