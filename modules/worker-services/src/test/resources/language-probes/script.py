import os
import sys
def walk(root):
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if os.path.isdir(path):
            yield from walk(path)
        else:
            yield path
for item in walk(sys.argv[1]):
    print(item)
