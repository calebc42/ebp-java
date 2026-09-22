# M2 evidence manifest: transport profiles

Executed 2026-09-22 on the WSL2 host recorded in `../m1/MANIFEST.md`.  No
device was attached; every device gate is listed under "Pending" and was
not claimed.  Design record: `ebp/slop-docs/EMACS-UNIX-AGENT-kit.md`
(amendment #191).

## Source revisions

| Repo | Commit |
|---|---|
| `ebp` | `1ef9858` spec: amendment #191 (on top of `7ede3b4`, the #188–#190 checkpoint) |
| `ebp.el` | `f65bfb1` emacs-unix binder over a shared binder pool (on top of `be8cc23`, the transport checkpoint) |
| `ebp-java` | `deadd95` host transports, plus the agent commit that carries this manifest |
| `emacs/emacs` (jetpacs-native) | `df6f1fc4902` M1 host checkpoint, plus the agent commit |

## Host gates

- `ebp`: `python3 validate.py` green after #191 (49 frames, 17 wire fixtures x3 chunkings, KAT reproduced).
- `ebp.el`: `test/run-tests.sh` green, seven suites (`ebp-el-tests.log`): 91 + 24 + 11 + 45 + 30 + 15 (websocket, after the pool extraction) + 13 (the new `ebp-unix-test`).  The Unix suite also passes on the Emacs 30.1 floor (`/usr/bin/emacs`), which is the repository's declared minimum.
- `ebp-java`: `./test.sh` green, 52 JUnit tests including `PreauthPoolTest`; `test/run-lisp-tests.sh` green (13 ERT tests, byte-compile with warnings as errors).
- `test/host_e2e.py` (`host-e2e.log`): the five M1 scenarios plus scenario 6 (`loopback-unix`: runner binds a 0600 socket in a private directory, ebp.el dials, READY, one durable activation, runner relaunch rebinds the same path, socket unlinked on quit) and scenario 7 (`emacs-unix`: ebp.el binds, runner dials, READY, one durable activation, Emacs listener torn down and rebound, the SPEC 5.7 jittered redial reaches READY a second time).  `RESULT PASS`.

## Defects found and fixed on the way

- `Channels.newInputStream`/`newOutputStream` over a blocking `SocketChannel` serialize reads and writes on the channel's blocking lock, so the Companion's sink write stalled behind the reader for as long as Emacs was silent (scenario 6 timed out on `tap-id`).  `UnixChannelStreams` calls the channel directly.
- In batch Emacs a write to a socket whose peer already closed is fatal (SIGPIPE is not ignored), which killed the `ebp-unix` suite when the liveness probe closed before the listener's hello.  The probe now waits for the listener's first bytes.

## Pending (device)

1. `:agent` process runs without `libemacs.so` mapped (`/proc/<pid>/maps`).
2. `agent.sock` exists mode 0600 in a 0700 directory under the package's files directory.
3. `M-x ebpm1-android-start-agent` reaches READY over `loopback-unix`.
4. `kill` of the default process (not `am force-stop`): the agent survives, a queued admission persists in `agent.sqlite`, `queued_events` grows on the next welcome.
5. A foreign UID (`run-as` another debuggable package, or `adb shell`) is refused at accept by the peer-credential check.
6. Bound-service wake: with `wake-target` = `bound-service` configured, a durable `wake` admission binds `EmacsWakeService` and starts Emacs; record whether the foreground-service start is refused on the device's Android version.
