# M1 evidence manifest

Milestone: `ebp-java-canvas-milestone-plan.md` (EBP Java + Android Emacs
Canvas, first milestone).  Executed 2026-09-22 on the WSL2 host recorded
below.  No device was attached during this execution; every gate that
needs a device is listed under "Pending" and was not claimed.

## Source revisions

Baseline HEADs (see `baseline.md`, re-verified at execution time):

| Repo | HEAD at start | Working tree at start | Working tree at end |
|---|---|---|---|
| `ebp` | c8b45a51b599… | dirty (`ebp.status.txt`, `ebp.diff`, `ebp.untracked.txt`) | unchanged by this work |
| `ebp.el` | a47d2b6ac5d1… | dirty (`ebp-el.status.txt`, `ebp-el.diff`) | plus the revision-persistence seam, uncommitted (`ebp-el.after-seam.diff`, `ebp-el.after-seam.status.txt`) |
| `ebp-kmp` | 5bcdd9dd1c38… | clean | unchanged |
| `ebp-compose` | 8fb426fb0818… | clean | unchanged |
| `jetpacs-native` | 53a9f7564343… | clean | unchanged |
| `emacs/emacs` (jetpacs-native fork) | 328b21aa6c56… | clean | modified, uncommitted (`emacs-fork.diff`, `emacs-fork.status.txt`, `emacs-fork.vendored-files.txt`) |
| `ebp-java` (new) | — | — | `ebp-java.head.txt` |

Nothing in `ebp`, `ebp.el`, or `emacs/emacs` was committed: those trees
carried the user's own uncommitted edits before this work began, so the
changes are left in place and captured here as diffs.  `ebp-java` is a
new repository with one focused commit per plan step.

## Toolchain and environment

- Host: Linux 6.18 (WSL2), 24 cores; Emacs 31.1 at `/home/calebc42/.local/bin/emacs` (SQLite and native JSON available).
- Java: Eclipse Adoptium JDK 17 (`~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`); the core and the Emacs Android build both compile with `--release 7`.
- Android: NDK 27.2.12479018 (r27c), `platforms/android-37.0/android.jar`, build-tools 37.0.0, `aarch64-linux-android30-clang`; APK is arm64-v8a, minSdk 30, Emacs 32.0.50, debuggable (`--with-android-debug`).
- Python 3.13.5 for the vocabulary generator and the end-to-end harness.

## Step A — baseline freeze

- `baseline-build.log`: the pre-existing Android tree rebuilt cleanly (`exit=0`).
- SQLite: the AOSP `platform/external/sqlite` dist tree (3.44.4) was fetched to `~/workspace/android-deps/sqlite` and given an `Android.mk` (the AOSP tree ships only `Android.bp`; Emacs's configure maps `sqlite3` to module `libsqlite_static_minimal`).  `sqlite-configure-build.log`: reconfigured with `--with-ndk-path=/home/calebc42/workspace/android-deps/sqlite`; `cross/src/config.h` now defines `HAVE_SQLITE3 1`; `libsqlite_static_minimal.a` built and linked (`sqlite3_open_v2` present in the packaged `libemacs.so`).

## Step B — Java protocol harness

- `test.sh` → `JUnit 4.13.2 … OK (48 tests)`: all 18 `goldens/wire` fixtures at whole/1-octet/7-octet chunking, the SPEC 9.3 known-answer vector, encoder byte syntax, pipelined split points, the 4 MiB body in 8 KiB reads, a good frame ahead of a bad one, request-id grammar, registry drift against `contract.json`, strict-JSON cases.
- `host-e2e.log`: the real `ebp.el` peer authenticated and reached `READY` against the Java Companion.

## Step C — durable endpoint slice

- Java: `RecoveryTest` (file store, deterministic failpoints): crash after outbox commit before send → same EventId replayed; crash after result before delete → redelivered, Emacs answers `duplicate`; crash during the snapshot transaction → old complete state, and after commit-before-reply → new complete state with the resend answered `stale`; whole process death restores cache, outbox (FIFO), and a fresh session; queue dedupe, in-flight protection, EventId non-reuse, capacity, expiry under a backward wall-clock step, counters surviving reopen; store format versioned and failing closed.
- ebp.el: the revision-persistence seam (schema v2 `surface_floors`, `ebp-store-surface-floors`, `ebp-store-raise-floor`, send-time floor commit in `ebp-client--surface-request`, rising absorb in the welcome).  `ebp-el-tests.log`: namespace guard, byte-compile with warnings as errors, and the suites `ebp-wire-test`, `ebp-store-test`, `ebp-revision-test` (new, 11 tests), `ebp-sync-test`, `ebp-complete-test`.

## Step D — native host and renderer

- C bridge: `src/androidfns.c` (+445 lines, `emacs-fork.diff`): `android-ebp-start`, `-stop`, `-credentials`, `-status`, `-snapshot`, `-activate`, `-arm-failpoint`.  Compiled by the cross build; all seven DEFUN names are present in the packaged `libemacs.so`.
- Java in the APK: `java/Makefile.in` lists the vendored `java/com/calebc42/ebp/companion/**` sources; `java/proguard.conf` keeps `org.gnu.emacs.EmacsEbpHost` (R8 had shrunk it away as unreachable from Java, `ebp-apk-build.log`); `ebp-apk-build-2.log`: `make exit=0`, `classes.dex` 274,000 bytes carrying 79 Companion/host classes, zero compiler warnings.
- APK: `java/emacs-32.0.50-30-arm64-v8a.apk`, 55,228,097 bytes, SHA-256 in `apk.sha256` (`bf9f079a…`).
- Renderer: `lisp/ebpm1-android.el` (byte-compiles clean on the host; not exercised on a device).

## Step E — end-to-end demo (host)

`host_e2e.py` with the unmodified `ebp.el` client and `ebpm1-app.el` against the host runner (`host-e2e.log`, `host-e2e.stdout`, `RESULT PASS`):

1. fresh pairing → `READY`; accepted screen text `["EBP milestone", "Flag: off"]`, buttons `set-on`/`set-off`, indicator raster first pixel `#ffc62828`;
2. `Set on` admitted durably (`QUEUED`), delivered as `event.action` with `queued_at_ms`, Emacs committed `desired=t` and republished; revision 0 → 1, first pixel `#ff2e7d32`, queue drained;
3. Emacs stopped; `Set off` admitted with no session (`queued_events` 1); cached screen still readable; relaunched Emacs replayed it during `SYNCING`, committed `desired=false`, republished (revision 3);
4. failpoint `outbox.after-commit-before-send` → Companion halted (exit 3) with one committed outbox record; relaunched Companion reported `queued_events` 1 and nothing in flight; the peer redialed, replay delivered the retained record, Emacs accepted, the EventId stays reserved;
5. Companion store erased → Emacs republished at revision 6, above the previous 3 (persisted floors, not the Companion's report, govern).

Touch and keyboard activation share one code path (`ebpm1-android--button-action` → `android-ebp-activate`); on the host both are represented by the runner's `tap-id`.

## Step F — failure and restart gates

Covered on host stores and the host runner as listed above.  The
Android `AndroidSqliteStore` path and the C bridge are compiled into the
APK but not executed here.

## Application tests

`ebpm1-lisp-tests.log`: `ebpm1-app.el` and `ebpm1-android.el` byte-compile with warnings as errors; the ERT suite covers authored members ⊆ advertised, handler validation, idempotent set, replay at an older revision, flag persistence across reopen, and the retryable storage-failure answer.

## Commands

```sh
cd ~/workspace/ebp-java && tools/fetch-test-deps.sh && ./test.sh
cd ~/workspace/ebp-java && EMACS=~/.local/bin/emacs test/run-lisp-tests.sh
cd ~/workspace/ebp-java && EMACS=~/.local/bin/emacs python3 test/host_e2e.py
cd ~/workspace/ebp.el && test/run-tests.sh
cd ~/workspace/emacs/emacs && . ~/workspace/jetpacs-native/env.sh && \
  ./configure --with-android=$EMACS_ANDROID_JAR ANDROID_CC=$EMACS_ANDROID_CC \
    SDK_BUILD_TOOLS=$EMACS_SDK_BUILD_TOOLS --with-android-debug --with-gnutls=ifavailable \
    "--with-ndk-path=$HOME/workspace/android-deps/sqlite" && make all -j24
cd ~/workspace/ebp-java && tools/sync-emacs.sh && make -C ~/workspace/emacs/emacs all -j24
```

Logs omit pairing tokens: the host runner prints only `PAIRING=<id>`; the
token lives in the harness's temporary `pairing.json` and is deleted with it.

## Pending (device gates, not claimed)

- Install the APK, complete the first-run dump, confirm an ordinary buffer and the Canvas smoke test work (`jetpacs-native/report.org` gates 1-2 were already pending).
- `sqlite-available-p` and `ebp-sqlite-open` on device; `M-x ebpm1-android-start`; touch and keyboard activation; Unicode labels; resize/reopen; the AndroidSqliteStore recovery matrix with `android-ebp-arm-failpoint` (debuggable build) and explicit relaunch; the force-stop versus process-death distinction.
- Warm interaction latency (median and p95 over ≥ 50 activations) and resource high-water marks.
- The recording.

## Compatibility

See `README.md` ("Compatibility table"): an experimental partial endpoint
implementing three of the eight Core Node Set types plus `canvas`; not
claiming SPEC 24.1 conformance.
