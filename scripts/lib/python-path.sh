# Sourced, not run: `. "$repo/scripts/lib/python-path.sh"`, then
#   PYTHONPATH=$(js_pythonpath "$repo") python ...
# prints the PYTHONPATH naming the jseval and jsharness packages of the checkout at <root>.
# Delegates to python-path.cjs beside this file, the single definition; under Git Bash the
# Windows node receives the converted path and answers with Windows separators, which is
# what the Windows python reads.
js_pythonpath() {
  [[ $# -eq 1 && -n $1 ]] || { echo "usage: js_pythonpath <checkout-root>" >&2; return 2; }
  node "${BASH_SOURCE[0]%/*}/python-path.cjs" "$1"
}
