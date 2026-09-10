# Operating System Simulator

A Java operating system simulator developed for **ICSI 412**. It models user processes interacting with a kernel through system calls, with scheduling, device I/O, message passing, and virtual memory.

## Implemented concepts

- **Processes and system calls:** Java threads and semaphores model user/kernel execution, process creation, PID lookup, and exit.
- **Scheduling:** realtime, interactive, and background ready queues with weighted random selection, timer-requested cooperative switching, timeout-based demotion, and sleeping-process wakeups.
- **Interprocess communication:** per-process mailboxes, PID/name lookup, message delivery, and blocking/wakeup support for message receivers.
- **Device I/O:** a virtual file system routes open, close, read, seek, and write calls to seeded/unseeded random devices and host-file-backed storage, using per-process descriptor tables.
- **Virtual memory:** page-aligned allocation and freeing, per-process page tables, a two-entry TLB, demand allocation, invalid-page fault handling, and randomized page eviction with disk-backed swap. Simulated physical memory is 1 MiB with 1 KiB pages.

## Components and structure

| Location | Purpose |
| --- | --- |
| `src/Main.java` | Original entry point; starts the swap stress scenario |
| `src/OS.java`, `src/Kernel.java` | System-call interface and kernel services |
| `src/Process.java`, `src/UserlandProcess.java`, `src/PCB.java`, `src/Scheduler.java` | Process execution, state, and scheduling |
| `src/Hardware.java`, `src/VirtualtoPhysicalMapping.java` | Simulated memory, address translation, and page mappings |
| `src/Device.java`, `src/VFS.java`, `src/RandomDevice.java`, `src/FakeFileSystem.java` | Device abstraction and I/O implementations |
| `src/KernelMessage.java`, `src/Ping.java`, `src/Pong.java` | Message representation and IPC demo |
| `src/Init*.java`, other sample/test processes | Original device, process, paging, and swap scenarios |
| `tools/RunScenario.java` | Command-line launcher for the original scenarios |
| `tools/test.ps1` | Bounded scenario runs with captured output |
| `tests/RegressionTests.java`, `tools/regress.ps1` | Focused coordination, paging, scheduling, and cleanup regressions |
| `tools/stress.ps1` | Repeated scenarios in concurrent, isolated JVMs |

Built with Java standard-library threads, semaphores, timers, collections, and `RandomAccessFile`. No external libraries, Maven, or Gradle are required.

## Build and run

Install a **JDK 21** and put its `bin` directory on `PATH`. From the repository root:

```sh
javac -encoding UTF-8 -d build/classes src/*.java tools/RunScenario.java
java -cp build/classes Main
```

The simulator keeps running after its workloads finish; use **Ctrl+C** to stop it. Runs create `swap.bin` and, for file scenarios, `simple.dat` and `shared.dat` in the working directory. These generated files are ignored by Git.

Launch other existing scenarios without changing the source:

```sh
java -cp build/classes RunScenario Init
java -cp build/classes RunScenario InitPagingTests
java -cp build/classes RunScenario InitPingPong
java -cp build/classes RunScenario MemoryReadWriteTest
java -cp build/classes RunScenario TestRandomOnce "random 42"
```

`Init` runs device/file scenarios, `InitPagingTests` runs allocation and fault scenarios, and `InitPingPong` runs the message exchange. `InitSwapTests` starts a memory smoke test and 20 memory-intensive processes; it is also the default workload in `Main`.

## Verification

The existing tests are console programs, including memory checks that print their results. On Windows, run the checked scenarios, focused regressions, and repeated runs with PowerShell:

```powershell
./tools/test.ps1
./tools/regress.ps1
./tools/stress.ps1 -Repetitions 5 -Concurrency 4
# Or select a JDK explicitly:
./tools/test.ps1 -JavaHome 'C:/path/to/jdk-21' -Seconds 60
```

The scenario script compiles the project and checks 21 isolated JVM runs against their expected output, workload completion, and process-thread cleanup. Each run has a 60-second limit; failures and timeouts return a nonzero status. Logs and summaries are saved under `build/`. The individual file-reader run starts with an empty file; the `Init` group includes the writer and both readers.

Verified on Windows with Amazon Corretto 21.0.9: **21/21 scenarios**, **10/10 focused regressions**, and **30/30 repeated runs** passed. The page-82 regression verifies data under swap pressure, and the intentional invalid-memory scenario faults as expected. See [verification details](VERIFICATION.md) and the [fix summary](FIXES.md).

The simulator uses cooperative execution within one OS instance per JVM. Scheduling, eviction order, and run duration vary; the kernel and idle process intentionally remain alive until stopped.

Originally developed for ICSI 412 at the University at Albany and later validated and refined for portfolio publication.
