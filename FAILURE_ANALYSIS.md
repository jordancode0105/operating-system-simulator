# Verification failure analysis

> Historical analysis of the implementation before the September 10, 2026 fixes. The confirmed issues below have now been addressed; see [FIXES.md](FIXES.md) and the current [verification results](VERIFICATION.md). Source line numbers below refer to the original version.

The failures arise from several shared defects in the original simulator, plus limitations in interpreting its console scenarios. The new runner exposes startup races more frequently when a short test process is used as the initial process, but it does not introduce those races. The original `Main` exhibits them too.

No implementation, existing test, launcher, or test-script code was changed during this analysis. All 36 original Java files still match their preserved SHA-256 hashes. Diagnostic programs and logs are in the ignored local `build/analysis/` directory.

## Evidence and scope

The starting observations are the 21 runs recorded in `VERIFICATION.md` and `build/test-results/summary.csv`. Follow-up work used source inspection, external JDI debugger snapshots, repeated original entry-point runs, and controlled fixtures invoking original methods. Debugger suspension changes timing, so follow-up results identify possible execution paths rather than reconstructing every instruction of an earlier run.

The only project documentation present is the portfolio README and verification report added during preparation. No original assignment specification, grading assertions, or separate test framework was found. Original comments and console checks are therefore the available statements of intended behavior.

Key additional observations:

- A controlled fixture reproduced the paging mismatch exactly: expected **17**, actual **34**, with the second process's virtual page 0 still nonresident while its stale TLB translation referenced a frame subsequently allocated to virtual page 2.
- A scheduler fixture left **one interactive process ready and `currentlyRunning == null`**. A separate timer fixture woke a process into the ready queue without dispatching it.
- A live standalone Piggy run remained stalled at its first sleep at the 3-, 13-, and 23-second snapshots, with no idle process, one ready Piggy, and no current process.
- Another Piggy run printed `wrote 100 pages`, `verify OK`, and `freed=true`; its JVM then correctly remained alive. A third completed that work but also reproduced the startup cast exception.
- IPC snapshots showed a pending reply in Ping's mailbox while its wait loop repeatedly submitted `SwitchProcess`. Another run had a ready receiver with a pending message but no running process and no idle process.
- A 34-second run of the original `Main` produced **985 evictions, 11 successful Piggy verifications, and 9 Piggy verification failures**. This was not an all-pass swap run.
- A controlled, serialized invocation of the original allocation/mapping/swap services verified **2,000 first-byte values across 20 processes with 100 pages each, with zero mismatches**. That fixture explicitly cleared the TLB and requested a mapping before each access; it isolates swap services and is not a passing result for the concurrent simulator.
- Six additional 18-second runs of original `Main`, with exception observation but no periodic debugger snapshots, did not reproduce the historical page-82 exception. Two reproduced the Boolean-to-Integer startup exception; other runs included stalls or memory-check failures.

## A. Startup and system-call ownership: confirmed implementation defect

**Failure point:** `OS.createProcess`, line 66, casts the shared `OS.retVal` to `int`/`Integer`.

**Why:** `OS.parameters`, `OS.currentCall`, and `OS.retVal` are single static values shared by the host main thread, the kernel, and every simulated process (`OS.java:7–8,25`). `OS.Startup` creates the initial process at line 44 and only then attempts to create idle at line 46. The kernel starts the initial process before the host thread is guaranteed to have consumed its PID (`Kernel.java:50–55`). That process can issue more calls, replacing the initial PID result.

For the original seven cast failures:

| Scenario | Value seen where a PID was expected | Actual producer |
| --- | --- | --- |
| `Main` | `Boolean` | `MemSmoke`'s `OS.FreeMemory` result |
| `MemoryReadWriteTest` | `Boolean` | Its successful `OS.FreeMemory` result |
| `MemSmoke` | `Boolean` | Its successful `OS.FreeMemory` result |
| `TestRandomOnce random` | `byte[]` | Its `OS.Read` result |
| `TestRandomOnce random 42` | `byte[]` | Its `OS.Read` result |
| `TestFileWriteRead` | `byte[]` | Its `OS.Read` result; this trace is in the idle-creation call at `OS.Startup:46` |
| `TestFileReader` | `byte[]` | Its `OS.Read` result, even when the array is empty |

The other six traces are in the initial creation call at `OS.Startup:44`. `Kernel.java:32` produces the byte array, and `Kernel.java:43` produces the Boolean. `OS.Exit` does not clear `retVal`, so the last result can remain after the test body has finished.

The handoff itself also has an ownership race: `OS.startTheKernel` releases the kernel at line 29 **before** fetching the current PCB at line 30. A scheduling system call can change that PCB before the caller blocks. Calling `running.stop()` then acquires that PCB's semaphore on the calling Java thread; it does not stop a remote thread. During bootstrap, the host thread has no PCB of its own and can compete with a user process for its gate. A null current PCB skips the wait altogether.

`Process.isStopped` (`Process.java:18–19`) treats zero available permits as proof that a process has stopped. A running thread also has zero permits after acquiring its gate. The controlled fixture observed `isStopped=true` while the process was still executing its body, contradicting the acknowledgement implied by the comment above `PCB.stop`.

These are original synchronization/ownership defects, not malformed command-line arguments or reflection errors. Merely using a different cast, increasing timeouts, or making `retVal` volatile would not establish per-call ownership. A coordinated startup and syscall handoff change is warranted before interpreting these tests as reliable integration checks.

## B. Paging mismatch: confirmed implementation defect

**Failure point:** `MemoryExtendTest.java:29–30`, checking region 1 after writing the second region. The original run read **34 (`0x22`) instead of 17 (`0x11`)**.

`Hardware` has one global TLB indexed only by virtual page number (`Hardware.java:9,60–66`). It must not retain one process's translations when a different process executes. The comment at `Hardware.java:78` explicitly says it is cleared on context switch.

The actual clearing occurs only in `Kernel.SwitchProcess` (`Kernel.java:60–62`). Scheduler changes caused by sleep, exit, message blocking, and initial selection bypass that wrapper. `Kernel.FreeMemory` and `freeAllMemory` (`Kernel.java:320,351`) also release frames without invalidating cached translations.

The paging group runs `MemoryReadWriteTest` before `MemoryExtendTest`. The former frees its two pages and exits. A possible remaining cache contains VPN 0 → frame 0 and VPN 1 → frame 1. The next process allocates its virtual regions lazily, so its page-table entries initially have no resident frames. Its first writes hit the previous process's cached mappings, bypassing physical allocation. When VPN 2 first needs a frame, the allocator can hand it frame 0, which is still marked free. Writes of `0x22` to that page overwrite region 1's `0x11` data.

The fixture reproduced that exact aliasing with original methods. Random TLB replacement and intervening explicit switches explain why the standalone extension test and some group reruns pass. Allocation sizes, patterns, and expectations in `MemoryExtendTest` are valid. TLB invalidation and process handoff changes are warranted; changing the test's expected value is not.

## C. No mapping during swap: real failed invariant; exact historical interleaving uncertain

**Failure point:** `Hardware.translate`, line 52, reached from `Hardware.Write:30` → `Piggy.main:18`. After `OS.GetMapping(82)` returned, the second TLB lookup still did not contain virtual page 82 (`Hardware.java:49–52`).

This is not `MemoryFaultTest` or an intentionally invalid access. Piggy successfully requests 100 virtual pages and writes pages 0 through 99 before freeing anything. Page 82 is within that allocation. The original swap log contains neither the explicit unallocated-page diagnostic nor the out-of-memory/no-victim diagnostic from `Kernel.GetMapping`.

`OS.GetMapping` has no completion/result acknowledgement beyond the same flawed process-gate handoff (`OS.java:177–183`). Successful kernel handling ends by installing a TLB entry (`Kernel.java:275`), so that entry must either never have been installed for the caller, or have been removed/replaced before the caller's second lookup. Shared syscall ownership, incorrect resumption, and global TLB handling provide concrete ways that contract can fail.

The old trace does not include scheduler state or a syscall history, and the exact exception did not recur in the diagnostic runs. It therefore does **not** support claiming one specific lost-request or intervening-switch sequence as established fact. The classification is a real simulator failure on valid input, with the precise handoff/cache interleaving **uncertain**. The shared defects in A and B warrant changes; a separate rewrite of swap-file I/O is not justified by this trace or the passing serialized probe.

The later Piggy mismatches under the original `Main` are consistent with B: processes repeatedly sleep between accesses, and the next process can reuse another process's global TLB entry. The serialized probe demonstrates that the original swap services can preserve data when process identity and mappings are supplied consistently; it does not prove them correct for every case.

## D. Scheduler stalls: confirmed implementation defects

`Scheduler.chooseNextProcess` can select an empty priority queue and return null despite runnable processes in another queue (`Scheduler.java:189–222`). For example, with only interactive work ready, the one-in-four background branch at line 217 returns null. The fixture forced that branch using the scheduler's existing random generator and seed 256.

Once `currentlyRunning` is null, the timer only moves awakened sleepers into a ready queue (`Scheduler.java:56–59`). It neither selects nor starts a process. Its stop request at line 65 also requires a non-null current process. Meanwhile, the kernel parks at `Kernel.java:55`. No thread remains to make the next syscall and resume scheduling.

This combines with A during startup: the init process can reach its first sleep while the host is still polling for the initial PID, before idle is registered. The reproduced standalone Piggy stall had exactly this state: Piggy blocked in `OS.Sleep` at `Piggy.java:19`, host main polling at `OS.java:64`, kernel parked, one ready process, no current process, and no idle process. That state remained unchanged through 23 seconds. Two swap-group runs similarly left 20 ready Piggy processes with no current process; an IPC run left a ready receiver with a pending reply.

A standalone Piggy can also finish successfully when startup avoids this interleaving. Its original ten-second limit was not enough evidence by itself, but the matching persistent stall was subsequently reproduced. Startup ordering, queue fallback, and dispatch when no process is running require attention; a longer harness timeout alone cannot repair these states.

The timer requests cooperative yielding; it does not forcibly suspend arbitrary Java code. Also, `PCB.incTimeouts` is never called, so the priority-demotion branch cannot be reached through the supplied scheduling code. That is an additional original implementation gap, not a demonstrated cause of the particular saved failures, and no existing scenario directly checks demotion.

## E. IPC wait retry: confirmed implementation defect

`Kernel.WaitForMessage` blocks the receiver in scheduler metadata at line 199 and immediately polls again at line 202. Contrary to its comment, control has not waited for a send and rescheduling: it is still executing on the kernel thread. On an empty mailbox this normally returns null.

`OS.WaitForMessage` sets `currentCall = WaitForMessage` once, before its retry loop (`OS.java:153–159`). Other processes and idle then overwrite that shared field. When the receiver wakes and still sees null, its next iteration releases the kernel without restoring the intended call type. The kernel can repeatedly service `SwitchProcess` instead of retrieving the message.

The 3-, 13-, 23-, and 33-second IPC snapshots showed Ping with one queued reply and `retVal=null`, while the shared call was `SwitchProcess`; Pong was blocked waiting for the next reply. This is a lost request/livelock, not merely an unanswered final message or a short observation window. `Ping` and `Pong`'s loops support five replies each: the final extra Ping send may be dropped after Pong exits, but it does not explain failure before the exchange completes.

The exact saved run printed only Ping's discovery line, so its log alone cannot distinguish an early scheduler stall (D) from other startup/handoff failures. Both D and E were demonstrated in follow-up IPC runs. Using the original `InitPingPong` is the appropriate invocation; the runner did not start Ping and Pong independently or omit a required peer. Changes to IPC request ownership/retry and scheduling are warranted.

## F. Termination, intentional faults, and harness interpretation

**JVM remaining alive is expected.** `OS.Startup:45` explicitly says idle keeps the OS alive, and `IdleProcess.main` loops forever. `Kernel.main` also loops indefinitely. `OS.Exit` is a simulated-process operation, not a JVM shutdown. All 21 ten-second process kills are therefore not 21 failures, and no `System.exit` should be added to the simulator merely to make the harness finish.

**There is also a real host-thread lifecycle limitation.** `Scheduler.removeProcess` removes the PCB and releases its devices/memory, but does not terminate or release its underlying Java thread. In follow-up runs, removed processes remained parked at `Process.stop:35` → `PCB.stop:48` → `OS.Exit:83` after disappearing from the scheduler's PID map. The empty `finally` in `Process.run` does not perform termination notification or cleanup. This warrants a separate lifecycle change if exited simulated processes should release their Java-thread resources. It does not turn successful console output into a failure or explain the intentionally persistent idle JVM. Original comments specify resource removal but no detailed host-thread termination contract, so the precise intended shutdown design is not recoverable from the project.

**The page fault is expected.** `MemoryFaultTest` explicitly requests unallocated virtual page 50 and states it will intentionally fault. The kernel's segmentation-fault message and absence of `THIS SHOULD NOT PRINT` satisfy that negative scenario. The fault diagnostic is not an uncaught exception. The same observation in `InitPagingTests` is independent of that group's real extension mismatch.

**The standalone reader has different fixture data.** The runner creates a new directory and launches `TestFileReader` against a file it has not seeded. `FakeFileSystem.Open` creates that file, so `[]` is correct. `Init` supplies `TestFileSeed` and both readers; that group returned bytes 1–8. Expecting those bytes in the standalone run would be a harness/fixture mistake, not a filesystem defect. Its byte-array-to-PID exception is still defect A.

**Nine capacity opens are expected.** VFS has ten entries, one occupied by the kernel's swap file. In these isolated capacity runs, 12 attempted opens correctly produce nine successes and three failures.

**The runner is an observation harness.** It passes valid constructors, starts a fresh JVM per scenario, compiles all sources, uses an absolute classpath, and calls the public `OS.Startup(UserlandProcess)` API once. No project document restricts that API to particular init subclasses. Directly bootstrapping a short leaf test differs from launching it through `Init`, so it must be described as a startup-inclusive integration run, not a pure test of its device or memory operation. Both modes expose original defects; the original `Main` is an independent control.

The PowerShell script has no pass/fail assertions or workload-completion detection, and it can exit successfully after observing child-thread exceptions. That was documented behavior, but it should not be used as a CI pass signal. If converted into an automated test suite, it needs expected-output checks, negative-test handling, fixture setup, error classification, and a distinction between workload completion and JVM termination. Those would be harness changes, not simulator fixes.

## All 21 scenarios

Status is based on the original recorded result plus diagnostic findings. **Pass** means the existing observable scenario expectation was met; it does not certify race freedom, exact sleep timing, or complete host-thread cleanup. **Change warranted** concerns the reason for that row's failed result. Shared lifecycle limitations described above are not repeated on every passing row. A–F refer to the analyses above.

| # | Scenario | Status | Root cause / classification | Change warranted |
| --- | --- | --- | --- | --- |
| 1 | `Main` | Fail | A: original startup reads the smoke test's Boolean as a PID. Original entry point, not runner-specific. | Yes: startup/syscall ownership. |
| 2 | `Init` | Pass, observed | Device, shared-file, and capacity outputs met expectations. JVM persistence is F. | No scenario-specific fix. |
| 3 | `InitPagingTests` | Fail | B: stale TLB aliases a freed/reassigned frame; the separate page-50 fault is expected. | Yes: TLB invalidation/context handling. |
| 4 | `InitPingPong` | Fail / stalled | D/E: runnable work can be stranded; wait retry can submit the wrong call with a reply already queued. Exact path of the old short log is not recoverable. | Yes: scheduling and IPC ownership/retry. |
| 5 | `InitSwapTests` | Fail | C: valid page 82 has no TLB mapping after the syscall. Precise historical interleaving uncertain; B/D defects and swap-data failures independently confirmed. | Yes: shared handoff/TLB/scheduler defects; no demonstrated need to rewrite swap I/O. |
| 6 | `PIDTest` | Pass, observed | Printed a valid PID; persistent JVM is expected. | No scenario-specific fix. |
| 7 | `SleepTest` | Pass, observed | Printed sleep then wake; original log does not measure elapsed sleep time. | No fix established by this result. |
| 8 | `ExitTest` | Pass, observed | Printed exit marker; `OS.Exit` is not JVM shutdown. See F for separate host-thread leak. | No JVM-shutdown change; lifecycle issue separate. |
| 9 | `HelloWorld` | Pass, observed | Expected greeting printed. | No scenario-specific fix. |
| 10 | `GoodbyeWorld` | Pass, observed | Expected greeting printed. | No scenario-specific fix. |
| 11 | `MemoryReadWriteTest` | Fail overall; memory check passed | A: startup reads successful `FreeMemory` Boolean as PID. | Yes: startup/syscall ownership, not the checked pattern. |
| 12 | `MemoryExtendTest` | Pass, observed | Both regions verified and freed in isolation. Group failure is B. | No standalone-test fix; shared B still required. |
| 13 | `MemoryFaultTest` | Pass, expected fault | F: intentionally unallocated page 50; forbidden continuation did not print. | No fix to suppress the expected fault. |
| 14 | `MemSmoke` | Fail overall; memory check passed | A: startup reads `FreeMemory` Boolean as PID. | Yes: startup/syscall ownership. |
| 15 | `Piggy 1` | Fail / intermittent stall | A/D: first sleep before idle exists; process becomes ready but is never dispatched. Other runs complete. | Yes: startup and scheduler progress; timeout alone is insufficient. |
| 16 | `TestRandomOnce random` | Fail overall; read completed | A: byte-array result consumed as PID. | Yes: startup/syscall ownership. |
| 17 | `TestRandomOnce random 42` | Fail overall; seeded read correct | A: byte-array result consumed as PID. | Yes: startup/syscall ownership. |
| 18 | `TestFileWriteRead` | Fail overall; file round trip passed | A: idle-creation call reads the file read's byte array as PID. | Yes: startup/syscall ownership. |
| 19 | `TestFileSeed` | Pass, observed | Generated the expected eight bytes; no console output is required. | No scenario-specific fix. |
| 20 | `TestFileReader` | Fail overall; empty read expected | A: startup cast defect. F: fresh unseeded file explains `[]`, a fixture distinction. | Yes: shared startup defect; seed harness only if testing seeded contents. |
| 21 | `TestOpenCapacity` | Pass, observed | F: nine VFS slots available after swap-file reservation. | No capacity change. |

These classifications justify targeted changes to shared simulator mechanisms, not rewriting the original test programs to hide their failures. No such implementation changes have been made.
