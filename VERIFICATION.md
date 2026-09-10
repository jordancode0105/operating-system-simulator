# Verification

Verified on **September 10, 2026**, on Windows with **Amazon Corretto OpenJDK 21.0.9+10-LTS**.

All 36 simulator sources compile successfully with the documented `javac` command. The final checked scenario suite passed **21/21**, focused regressions passed **10/10**, and five repetitions of six race-sensitive workloads passed **30/30** with up to four isolated JVMs running concurrently.

## Before and after

The original console programs were not edited. “Before” records the observed outcome from the [preserved baseline](docs/verification-before-fixes.md); it was an observation run, not an assertion-based unit-test suite. “After” requires expected output, no unexpected stderr, no remaining non-idle PCBs, and cleanup of workload Java threads.

| Scenario | Before | After |
| --- | --- | --- |
| Main | Startup cast exception | PASS |
| Init | Expected output | PASS |
| InitPagingTests | Region mismatch: expected 17, got 34 | PASS, including intended fault |
| InitPingPong | Incomplete exchange | PASS, five replies each |
| InitSwapTests | Missing mapping for valid page 82 | PASS, all 20 Piggy checks/free operations |
| PIDTest | Expected output | PASS |
| SleepTest | Sleep/wake output | PASS |
| ExitTest | Exit output; thread cleanup unchecked | PASS, thread cleaned up |
| HelloWorld | Expected output | PASS |
| GoodbyeWorld | Expected output | PASS |
| MemoryReadWriteTest | Memory check succeeded; startup exception | PASS |
| MemoryExtendTest | Isolated regions verified | PASS |
| MemoryFaultTest | Intended page-50 fault | PASS, expected fault and thread cleanup |
| MemSmoke | Memory check succeeded; startup exception | PASS |
| Piggy 1 | Incomplete; later analysis reproduced a stall | PASS |
| TestRandomOnce random | Read completed; startup exception | PASS |
| TestRandomOnce random 42 | Seeded read correct; startup exception | PASS |
| TestFileWriteRead | File round trip succeeded; startup exception | PASS |
| TestFileSeed | Expected file bytes | PASS |
| TestFileReader | Empty read; startup exception | PASS, expected empty fixture |
| TestOpenCapacity | Nine opens, three failures | PASS |

## Verification after each fix stage

| Stage | Full scenario result | What changed in the result |
| --- | --- | --- |
| Request ownership, startup, and IPC retry | 18/21 | All original cast-exception cases and IPC/standalone Piggy passed; paging group and both swap entry points still failed |
| TLB invalidation and replacement | 21/21 | Paging mismatch and swap workload failures eliminated in this run |
| Scheduler fallback, wakeup dispatch, and timeout counting | 21/21 | Original suite remained passing; deterministic scheduler regressions also passed |
| Process lifecycle cleanup, first 30-second window | 20/21 | Main exceeded the window while still producing swap evictions; no exception or mismatch was recorded |
| Lifecycle, final 60-second window with thread cleanup required | 21/21 | All workload checks and Java-thread cleanup passed |

The first version of the output checker also exposed a checker race: it read console output before observing workload completion. That checker was corrected to take its output snapshot afterward, and the entire first-stage suite was rerun to obtain the 18/21 result above. Preliminary logs remain under `build/test-results/`.

A startup regression initially used a 30-second limit for 480 deliberate process switches. Idle selections can consume a 250 ms quantum each. Its limit was raised to 90 seconds without removing any iterations or assertions; it then passed. The original Main timeout is retained above, rather than counted as a pass.

## Focused regressions

All ten cases in `tests/RegressionTests.java` passed:

| Case | Checks |
| --- | --- |
| startup | 24 workers × 20 rounds; running-state reporting, PID ownership, seeded byte-array results, Boolean free results, and 480 explicit yields |
| ipc | Receiver blocks before sender; 40 request/acknowledgement exchanges with intervening sleep and idle scheduling |
| cache | Free, sleep, and exit invalidate cached translations |
| memory | Three processes repeatedly allocate, zero-check, write, sleep, verify isolation, free, and reuse four pages |
| scheduler | Deterministic empty-queue branch; both priority-demotion transitions; sleeper dispatch with no idle process |
| lifecycle | 20 workers mix explicit exit and normal return; thread joins, descriptor release, frame release, and no execution after exit |
| free-fault | Access after free produces the intended fault, ends the thread, and releases resources |
| error-cleanup | A deliberately uncaught test exception remains observable while its process releases its thread, frames, and descriptors |
| sleep | A process is not resumed before its requested deadline |
| swap82 | 20 processes × 100 pages; checks both endpoints of every page and page 82 ten times per process, with confirmed disk eviction |

As a negative control, the new startup, cache, and scheduler regressions were also compiled against the preserved original sources. All three failed on the intended behavioral assertions: incorrect running-state reporting, stale translation after free, and selecting an empty queue while work was ready.

## Repeated and concurrent runs

`tools/stress.ps1 -Repetitions 5 -Concurrency 4` passed all **30 runs**: five each of Main, InitSwapTests, InitPagingTests, InitPingPong, Piggy, and the startup regression. Each JVM had its own working directory and generated files. These include ten swap-stress runs, each requiring all 20 Piggy verification/free messages, as well as the separate deterministic page-82 test.

No post-fix mapping exceptions, memory mismatches, unexpected process exceptions, or stranded workloads were observed in these completed runs. Random choices and finite repetitions do not establish correctness for every possible schedule.

## Reproduce

From the repository root, with JDK 21 available:

```powershell
./tools/test.ps1 -JavaHome 'C:/path/to/jdk-21'
./tools/regress.ps1 -JavaHome 'C:/path/to/jdk-21'
./tools/stress.ps1 -JavaHome 'C:/path/to/jdk-21' -Repetitions 5 -Concurrency 4
```

The scripts return a nonzero status for failures or timeouts. Logs and CSV summaries are written to unique directories under `build/test-results/`, `build/regressions/`, and `build/stress/`. The ordinary simulator still runs indefinitely; only the test adapters stop their JVMs after checking workload completion.

See [FIXES.md](FIXES.md) for changed files, design decisions, and remaining limitations.
