# ebp-java

A second, independent implementation of the EBP Companion role, in Java 7,
written for the milestone described in `ebp-java-canvas-milestone-plan.md`:
one real EBP exchange inside Android Emacs, with the Companion endpoint
hosted in the Emacs process and the accepted screen presented in an Emacs
buffer with a Canvas indicator.

**This is an experimental, partial endpoint.** It implements the wire,
authentication, session, surface, and durable-queue core exactly, and only
four node types.  It must not be presented as conforming or generally
available; see the compatibility table below.

The normative specification and conformance goldens are read from the
sibling [`../ebp`](../ebp) checkout.  The Kotlin reference implementation in
[`../ebp-kmp`](../ebp-kmp) is the source this port was derived from; the
same `goldens/wire` fixtures verify both.

## Layout

| Path | What it is |
|---|---|
| `src/main/java/com/calebc42/ebp/companion/json` | Strict RFC 8259 parser (SPEC 4.1/4.2/4.5), value model, serializer, SPEC 4.3 comparator |
| `.../wire` | Content-Length framing decoder/encoder (SPEC 6), the receiver error taxonomy, fixed limits |
| `.../rpc` | JSON-RPC envelope rules (SPEC 7), the SPEC 11 method registry, the SPEC 10.1 state machine |
| `.../auth` | Pairing token codec, nonces, HMAC-SHA256 proofs with constant-time comparison (SPEC 9) |
| `.../store` | The transactional durable-store SPI, a rollback-capable memory store, and a versioned atomic-replace file store |
| `.../engine` | Per-connection session engine, surface store (SPEC 13), durable queue and replay pump (SPEC 15), document validator (SPEC 16/17), action pipeline (SPEC 14), presentation flattening, the JNI bundle, failpoints, and the generated contract vocabulary |
| `.../host` | Loopback listener (SPEC 5.2), pairing provisioning, the JVM host runner, a software rasterizer |
| `android/` | The Android host (`org.gnu.emacs.EmacsEbpHost`), the SQLite store, and the `android.graphics` rasterizer; compiled only inside the Emacs tree |
| `lisp/` | `ebpm1-app.el` (the milestone application over ebp.el) and `ebpm1-android.el` (the Emacs renderer for Android) |
| `test/` | JUnit suites, the ERT suite, the host end-to-end harness |
| `tools/` | Vocabulary generator, source vendoring into the Emacs tree, test-dependency fetch |
| `evidence/m1/` | Baseline freeze, captured diffs, build logs, test logs, and the manifest |

## Build and test

```sh
tools/fetch-test-deps.sh          # JUnit 4 + Hamcrest into lib/ (host only)
./test.sh                         # core at --release 7, vocabulary drift check, 48 JUnit tests
test/run-lisp-tests.sh            # byte-compile + ERT for the Elisp application
EMACS=emacs python3 test/host_e2e.py   # real ebp.el peer against the host runner
```

The host runner alone:

```sh
./build.sh
java -cp build/classes com.calebc42.ebp.companion.host.HostRunner --dir /tmp/companion --port 8765
```

It prints `PORT=` and `PAIRING=`, writes the pairing token to
`/tmp/companion/pairing.json` (mode 0600), and reads commands on stdin:
`status`, `snapshot SURFACE`, `tap SURFACE PATH`, `tap-id SURFACE NODE-ID`,
`failpoint NAME`, `close-transport`, `quit`.

## Android integration

```sh
tools/sync-emacs.sh               # vendor core + android classes into ../emacs/emacs/java
```

`java/Makefile.in` in the Emacs fork lists the vendored sources; the C
bridge lives in `src/androidfns.c` (DEFUNs `android-ebp-start`, `-stop`,
`-credentials`, `-status`, `-snapshot`, `-activate`, `-arm-failpoint`).
Both are marked experimental and not for upstream submission.  On the
device: `M-x ebpm1-android-start` after loading `ebp.el`, `ebpm1-app.el`,
and `ebpm1-android.el`.  The Emacs build needs SQLite; see the plan and
`evidence/m1/` for the `--with-ndk-path` recipe.

## Compatibility table

Protocol 3, document 3.1.0-draft.  "Yes" means implemented and covered by
the tests in this repository; anything else is not advertised.

| Area | Status |
|---|---|
| SPEC 4 JSON model: strict grammar, duplicate members, depth 64, safe integers, finiteness, surrogates, structural equality | Yes |
| SPEC 6 framing, all `goldens/wire` fixtures at whole/1-octet/7-octet chunking, pipelined-fault drain | Yes |
| SPEC 7 envelope, request ids, dispatch/direction/class/state rules, `rpc.cancel` (no user-paced requests exist to cancel) | Yes |
| SPEC 9 pairing, KAT proofs, unknown-pairing equal-work path, constant-time comparison, fail-closed handshake | Yes |
| SPEC 9.1 revocation (`Companion.revokePairing`) | Store erasure only; no pairing UI |
| SPEC 10 session states, welcome (limits reservation checked at startup), synchronization barrier, `session.superseded` | Yes |
| SPEC 10.2 `input_state`, SPEC 13.6 drafts, SPEC 14.6 `state.changed` | No stateful node type is advertised, so none exist |
| SPEC 13 `surface.update` / `surface.remove` / `surface.release`, tombstones, limits, multi-view `current_view`, `stale_spec` validation | Yes (`stale_after_s` is stored, not yet timed on the host) |
| SPEC 14.1 remote descriptors, `confirm` validation, `open_surface` gating | Validated; `confirm` is not presented (no dialog UI), `open_surface` never advertised |
| SPEC 14.2 builtins | `view.switch` and `companion.settings.open` only |
| SPEC 14.4 `event.action` delivery, results, `queued_at_ms` discriminator | Yes |
| SPEC 15 durable queue: queue_seq FIFO, dedupe, TTL with rollback-proof effective clock, capacity, EventId non-reuse, single-flight pump, replay summary, SYNCING barrier | Yes |
| SPEC 15.1 `wake` policy | Not advertised (`offline.wake` never granted) |
| SPEC 16 node walk, identity, depth/count limits, universal attribute typing, strict `members`, unknown-type degrade | Yes |
| SPEC 17 node types | `text`, `column`, `button` (stateless), `canvas` (five op shapes).  Core Node Set members `row`, `box`, `spacer`, `divider`, `text_input` are **not** implemented |
| SPEC 18-21 optional modules, SPEC 20 device report | Not advertised |
| SPEC 22.3 bounded processing | Outstanding-request ring, bounded transport handoff; `1401` refusal on the sender ceiling |
| SPEC 24.1 Companion core conformance | **Not claimed** (partial node set) |

Emacs-side gap recorded for the milestone: the current `ebp.el` does not
implement the Emacs half of SPEC 10.2 `members` (omitting unadvertised
members); the milestone application authors within the advertised map and
`test/ebpm1-app-test.el` checks it.
