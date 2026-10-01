"""Inventory repository Python import dependencies without importing/running them.

Includes function-local imports conservatively: those are instrument dependencies,
not a sweep of unrelated scripts. External libraries remain machine/environment pins.
"""
import ast
import json
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
pending = [Path(p).resolve() for p in sys.argv[2:]]
seen = set()


def add_module(module):
    base = root.joinpath(*module.split('.'))
    for parent in [root / 'jseval', *base.parents]:
        if parent == root or root not in parent.parents:
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
    package = '.'.join(file.parent.relative_to(root).parts) if root in file.parents else ''
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
                if alias.name.startswith('jseval'):
                    add_module(alias.name)
        if isinstance(node, ast.ImportFrom):
            if node.level:
                parts = package.split('.')
                module = '.'.join(parts[:len(parts) - node.level + 1] + ([node.module] if node.module else []))
            else:
                module = node.module or ''
            if module.startswith('jseval'):
                add_module(module)
                for alias in node.names:
                    add_module(f'{module}.{alias.name}')

print(json.dumps(sorted(str(p) for p in seen)))
