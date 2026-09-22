#!/bin/sh
# Byte-compile the milestone Elisp with warnings as errors, then run its
# ERT suite.  Requires the neighbouring ebp.el checkout (EBP_EL_DIR) and
# an Emacs with SQLite support (EMACS, default `emacs').
set -eu

cd "$(dirname "$0")/.."
ebp_el=${EBP_EL_DIR:-"$(pwd)/../ebp.el/lisp"}
emacs=${EMACS:-emacs}
test -r "$ebp_el/ebp.el" || {
  echo "ebp.el not found at $ebp_el" >&2
  exit 2
}

compile_dir=$(mktemp -d)
trap 'rm -rf "$compile_dir"' EXIT
for source in lisp/*.el; do
  "$emacs" -Q --batch -L "$ebp_el" -L lisp \
    --eval "(progn
      (setq load-prefer-newer t byte-compile-error-on-warn t)
      (setq byte-compile-dest-file-function
            (lambda (file)
              (expand-file-name
               (concat (file-name-nondirectory file) \"c\")
               \"$compile_dir\"))))" \
    -f batch-byte-compile "$source"
done

"$emacs" -Q --batch -L "$ebp_el" -L lisp -L test \
  --eval '(setq load-prefer-newer t)' \
  -l test/ebpm1-app-test.el -f ert-run-tests-batch-and-exit
