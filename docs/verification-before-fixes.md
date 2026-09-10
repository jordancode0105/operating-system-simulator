# Verification

Verified on September 8, 2026, on Windows with Amazon Corretto OpenJDK 21.0.9+10-LTS.

## Build and source preservation

- Original layout: all 36 Java files compiled with `javac`, exit code **0**.
- Flattened layout: all original files plus `tools/RunScenario.java` compiled, exit code **0**. The README build command was also checked successfully.
- SHA-256 comparison after moving `Skeleton/OS/src/` to `src/`: **36 matches, 0 changed files**. Existing implementation, tests, comments, and authorship were preserved.

## Existing scenarios

Command: `./tools/test.ps1 -JavaHome 'C:/Users/fizzt/.jdks/corretto-21.0.9'`

All 21 JVM runs were stopped after the configured **10-second observation window**. The simulator does not shut down automatically, so these are observed results, not 21 passing unit tests. Each run used a fresh working directory. Full stdout/stderr and generated files remain locally in `build/test-results/`; `summary.csv` records each log location.

| Run | Observed result |
| --- | --- |
| `Main` | `MemSmoke` printed `OK` and `free=true`; startup threw `ClassCastException` (Boolean to Integer) in `OS.createProcess`, line 66. |
| `Init` | Both random devices returned eight bytes; file write/read returned `[10, 20, 30, 40]`; both readers returned `[1, 2, 3, 4, 5, 6, 7, 8]`; capacity printed `opened=9 failed=3`. No stderr. |
| `InitPagingTests` | Read/write printed `OK` and `free result = true`; extension printed `mismatch in region1 at 0 got=34`; fault scenario reported virtual page 50. No stderr. |
| `InitPingPong` | Printed only `I am PING, pong = 3`; message exchange did not complete within the window. No stderr. |
| `InitSwapTests` | Smoke test printed `OK` and `free=true`; all 20 Piggy processes printed allocation bases; 507 eviction messages. A process threw `IllegalStateException: No mapping for virtual page 82` in `Hardware.translate`, line 52. No Piggy verification completed within the window. |
| `PIDTest` | `PidTester: my PID is 1`; no stderr. |
| `SleepTest` | `Sleeping for 1000 ms`, then `Awake`; no stderr. |
| `ExitTest` | `Now exiting`; no stderr. |
| `HelloWorld` | `Hello World`; no stderr. |
| `GoodbyeWorld` | `Goodbye World`; no stderr. |
| `MemoryReadWriteTest` | `read/write OK` and `free result = true`; startup also threw Boolean-to-Integer `ClassCastException` in `OS.createProcess`, line 66. |
| `MemoryExtendTest` | Bases `0` and `1024`; `both regions OK`; `free1=true free2=true`; no stderr. |
| `MemoryFaultTest` | Reported `Segmentation fault in pid 1 at virtual page 50`; the forbidden follow-up message did not print. No stderr. |
| `MemSmoke` | `OK` and `free=true`; startup also threw Boolean-to-Integer `ClassCastException` in `OS.createProcess`, line 66. |
| `Piggy 1` | Printed only `[Piggy1] base=0`; verification did not complete within the window. No stderr. |
| `TestRandomOnce "random"` | Returned `[80, -54, 72, 80, 53, -106, -53, -25]`; startup also threw byte-array-to-Integer `ClassCastException` in `OS.createProcess`, line 66. |
| `TestRandomOnce "random 42"` | Returned `[-70, 13, -82, 12, 79, -15, 70, -75]`; startup also threw byte-array-to-Integer `ClassCastException` in `OS.createProcess`, line 66. |
| `TestFileWriteRead` | `fd=0`, `wrote=4 read=[10, 20, 30, 40]`; generated file contained those four bytes. Startup also threw byte-array-to-Integer `ClassCastException` in `OS.createProcess`, line 66. |
| `TestFileSeed` | No console output; generated `shared.dat` contained exactly bytes 1 through 8. No stderr. |
| `TestFileReader` | Printed `[File-R1] []` from a fresh file; startup also threw byte-array-to-Integer `ClassCastException` in `OS.createProcess`, line 66. The seeded reader behavior is exercised by `Init`. |
| `TestOpenCapacity` | `[Cap] opened=9 failed=3`; no stderr. |

Eight runs emitted exception traces; the combined paging run also printed a memory mismatch. The kernel reserves one of the ten VFS slots for `swap.bin`, explaining the nine successful opens in the capacity scenario. `Ping` and `Pong` were exercised together through `InitPingPong`; the idle process is started by every scenario.

Behavior varied across runs: before flattening, `Main` ran the memory smoke test and produced swap evictions without stderr during its 10-second window. An additional paging-group observation using identical source printed both successful memory checks and the intended page fault. The table retains the results of the complete post-move run, including its errors.

## Publication contents

Keep `src/`, `tools/`, `.gitignore`, `README.md`, and this report. The 36 source files include both the simulator and all original demos/tests; only a JDK is required to build them.

The redundant starter folders, IntelliJ configuration, macOS archive metadata, and old compiled output were moved to the ignored local `build/original-layout/` archive. The old nested `.gitignore` is retained there as well. The original `simple.dat`, `shared.dat`, and `swap.bin` remain locally and are ignored: the code generates them at runtime. No original files were permanently deleted. Git's untracked-file listing was checked using an isolated repository under `build/` to confirm the publication file set and exclusions.
