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
- The host tier (`src/host/java`) is the JVM runner and the desktop
  Unix-domain transports (SPEC 5.3 listener, SPEC 5.6 dialer).  It may use
  JDK 17 language and library level (`build.sh` compiles it with
  `--release 17` against the core) and is never vendored into the Emacs
  tree by `tools/sync-emacs.sh`.  Anything the APK needs stays in the core.
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
