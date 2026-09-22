# ebp-java implementation guide

The sibling `ebp` repository is normative; `ebp-kmp` is the reference
implementation this port was derived from.  Keep this implementation free
of Kotlin, coroutines, Compose, Room, Jetpacs application names, and
renderer-owned node names.

- The core (`src/main/java`) is Java 7 language and library level only:
  the GNU Emacs Android build compiles it with `--release 7`.  No lambdas,
  streams, `java.time`, `Optional`, or `java.util.Base64`.  `build.sh`
  enforces the level.
- The core depends on nothing outside the JDK.  Android classes live only
  under `android/` and are compiled inside the Emacs tree.
- Never hand-edit the generated `engine/Vocabulary.java`; update the
  contract in `ebp` and run `tools/gen-vocabulary.py`.  `test.sh` fails
  when the projection is stale.
- Storage acceptance must remain atomic, partitioned by pairing, ordered,
  and rollback-safe; no success response may precede its commit.
- Test-only failpoints (`engine/Failpoints.java`) are armed only by the
  host runner or a debuggable Android build's diagnostics entry, never by
  protocol traffic.
- This is an experimental partial Companion (see README's compatibility
  table).  Advertise only implemented behavior; do not widen the welcome
  without implementing and testing what it claims.

Run `./test.sh` and `test/run-lisp-tests.sh` before handing off changes;
`test/host_e2e.py` needs the sibling `ebp.el` checkout and an Emacs with
SQLite.
