# Correctness fixes

The confirmed defects from the original implementation have been addressed without replacing its OS/kernel/process/scheduler/device architecture. Six simulator source files changed; the other 30 original Java files, including every original console scenario, remain byte-for-byte unchanged. No swap-storage algorithm was rewritten.

## Design decisions

**System calls and startup.** Each request now carries its own call type, argument list, result, completion semaphore, and calling process. The kernel still executes on its original simulated process thread and resumes user processes through their gates. The host bootstrap registers init and idle before launching execution, and no caller polls a global return value. The old public request fields remain inspection snapshots, not the authority used to dispatch or return calls.

**Memory translation.** All scheduler selection paths clear the TLB. Freeing memory, releasing a process's memory, and reassigning an evicted frame invalidate translations. Installing a mapping removes any duplicate older entry. The missing-mapping exception remains in Hardware; it was not replaced with a retry that could conceal an unresolved error.

**Scheduler progress.** Weighted priority selection is preserved, with a fallback to another ready queue if the selected queue is empty. When the timer wakes work with no current process, it requests a kernel dispatch; it does not release a running user thread's gate. Timeout streaks are now counted and reset on blocking, enabling the original demotion logic. Timer behavior is factored into a small method for deterministic checks.

**IPC.** Every retry submits a fresh WaitForMessage request. Blocking changes the receiver's scheduler state; delivery wakes it, and the resumed caller retries its own operation. The old immediate second mailbox poll and inaccurate “when rescheduled” comment were corrected. Message format and routing are unchanged.

**Lifecycle.** A removed process is marked terminated and its gate released. A private control-flow signal unwinds only that process's Java thread. Explicit exit and invalid-memory termination use this path. Normal return and uncaught user-process errors also request kernel cleanup from the run-method's finally block. Unexpected exceptions still reach the uncaught-exception handler; regression tests confirm they are not swallowed. Kernel and idle threads intentionally remain alive.

## Exact changed and added files

| File | Change and reason |
| --- | --- |
| src/OS.java | Per-call ownership/completion, bootstrap ordering, explicit IPC retries, and cleanup notification on user-process completion |
| src/Kernel.java | Queue/dispatch owned requests, bootstrap registration, timer dispatch handling, result completion, and memory invalidation |
| src/Process.java | Identify the calling process; correct stopped/done state; synchronize thread creation; unwind exits and run cleanup on return/error |
| src/PCB.java | Remove the incorrect permit-count acknowledgement loop; expose internal ownership and termination delegation |
| src/Scheduler.java | TLB invalidation, nonempty-queue fallback, wakeup dispatch, timeout/demotion counting, and process termination |
| src/Hardware.java | Invalidate duplicate translations when programming a TLB slot |
| tools/test.ps1 | Run checked original scenarios, retain unique logs, require thread cleanup, and report failures/timeouts through exit status |
| tools/CheckScenario.java (new) | Assert original outputs, expected negative faults, file contents, completed workloads, and absence of leaked workload threads |
| tests/RegressionTests.java (new) | Ten focused regression cases, including deterministic page-82 coverage under swap pressure |
| tools/regress.ps1 (new) | Compile and run regressions in bounded isolated JVMs |
| tools/stress.ps1 (new) | Repeat race-sensitive scenarios concurrently with separate data directories and explicit pass/fail summaries |
| README.md | Update behavior, commands, structure, and current verified results |
| VERIFICATION.md | Record before/after results, intermediate failures, regression coverage, and concurrency checks |
| FAILURE_ANALYSIS.md | Mark the original analysis as historical and link to the fixes; preserve its findings |
| docs/verification-before-fixes.md (new) | Preserve the original detailed scenario results |
| FIXES.md (new) | Record this change rationale, file inventory, and remaining limits |

The existing tools/RunScenario.java, .gitignore, and all original sample/test implementations were preserved. Original source snapshots and preliminary diagnostic outputs remain locally under the ignored build/ directory.

## Results and remaining limits

The final results are **21/21 checked original scenarios, 10/10 regressions, and 30/30 repeated concurrent runs passing**. Each class of production fix was followed by the full original scenario suite. See [VERIFICATION.md](VERIFICATION.md) for the stage-by-stage results, including the initial checker correction and the 30-second stress timeout.

The historical page-82 exception's exact interleaving cannot be reconstructed. Following the established handoff and cache fixes, it did not recur in the completed swap stress runs or the deterministic page-82 regression. No evidence justified a separate swap-I/O rewrite.

Known operating limits remain:

- One simulator instance per JVM; OS.Startup is called once, and userland code invokes system calls from its simulated process thread.
- Scheduling is cooperative: arbitrary user code that never yields or makes a blocking call is not forcibly preempted.
- Scheduling and victim selection are randomized. Output order, eviction counts, and elapsed run time vary. A finite timeout can still be too short on a heavily loaded machine.
- Swap backing slots are allocated monotonically during a JVM run. This existing approach does not recycle disk slots after a process frees memory; RAM and device descriptors are released. Long-lived, repeatedly allocating simulations can grow the swap file.
- The automated PowerShell runners target Windows. The simulator and Java launch commands require only the JDK.

No remaining failing behavior was observed in the tested supported scenarios. The repository is ready for public GitHub publication as an educational simulator with these documented limits. This work prepared and verified local files; it did not publish a remote repository.
