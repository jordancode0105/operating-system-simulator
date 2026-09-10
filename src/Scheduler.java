import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Scheduler {

    // Ready queues
    private final LinkedList<PCB> realtime = new LinkedList<>();
    private final LinkedList<PCB> interactive = new LinkedList<>();
    private final LinkedList<PCB> background = new LinkedList<>();

    // Sleeping processes
    private final PriorityQueue<PCB> sleeping =
            new PriorityQueue<>(Comparator.comparingLong(p -> p.wakeTime));

    private final Timer timer;
    private final Random rand = new Random();

    // Currently scheduled to run
    public PCB currentlyRunning;

    private final Kernel kernel;

    //lookup structures for IPC and management
    private final Map<Integer, PCB> pidMap = new HashMap<>();   // pid -> pcb
    private final Set<Integer> waitingForMessage = new HashSet<>(); // pids blocked on WaitForMessage

    public Scheduler(Kernel kernel) {
        this.kernel = kernel;
        timer = new Timer("OS-Scheduler-Timer", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, 250, 250);
    }

    // Kept separate so timer behavior can be checked without wall-clock races.
    synchronized void tick() {
        long now = System.currentTimeMillis();
        //wake sleepers whose wakeTime has arrived
        while (!sleeping.isEmpty()) {
            PCB p = sleeping.peek();
            if (p == null) break;
            if (p.isDone()) {
                //finished while sleeping: close resources and drop from indexes
                kernel.closeAllFor(p);
                pidMap.remove(p.pid);
                waitingForMessage.remove(p.pid);
                sleeping.poll();
                continue;
            }

            if (p.wakeTime <= now) {
                p.wakeTime = 0L;
                sleeping.poll();
                addToQueue(p); // back to proper ready queue
            } else {
                break; // head not ready, others won't be either
            }
        }
        if (currentlyRunning != null) {
            currentlyRunning.requestStop();
            currentlyRunning.incTimeouts();
            if (currentlyRunning.getTimeouts() >= 5) {
                if (currentlyRunning.getPriority() == OS.PriorityType.realtime) {
                    currentlyRunning.setPriority(OS.PriorityType.interactive);
                } else if (currentlyRunning.getPriority() == OS.PriorityType.interactive) {
                    currentlyRunning.setPriority(OS.PriorityType.background);
                }
                currentlyRunning.resetTimeouts(); // reset streak after demotion
            }
        } else if (!realtime.isEmpty() || !interactive.isEmpty() || !background.isEmpty()) {
            // Let the kernel dispatch without racing an executing user process.
            kernel.requestDispatch();
        }
    }

    // accessor for the kernel
    public synchronized PCB getCurrentlyRunning() {
        return currentlyRunning;
    }

    // Create new PCB, index it by PID, and place it onto the correct ready queue
    public synchronized int createProcess(UserlandProcess up, OS.PriorityType p) {
        PCB pcb = new PCB(up, p);
        pidMap.put(pcb.pid, pcb);
        addToQueue(pcb);
        if (currentlyRunning == null) {
            switchProcess();
        }
        return pcb.pid;
    }

    // Core scheduling
    public synchronized void switchProcess() {
        long now = System.currentTimeMillis();

        // If current finished, clean up and forget it
        if (currentlyRunning != null && currentlyRunning.isDone()) {
            kernel.closeAllFor(currentlyRunning);
            kernel.freeAllMemory(currentlyRunning);
            pidMap.remove(currentlyRunning.pid);
            waitingForMessage.remove(currentlyRunning.pid);
            currentlyRunning = null;
        }

        // Requeue current if still alive and not sleeping
        if (currentlyRunning != null && !currentlyRunning.isDone() && currentlyRunning.wakeTime == 0L) {
            addToQueue(currentlyRunning);
        }

        // Choose next based on priority probabilities
        PCB next = chooseNextProcess();

        // Skip any finished PCBs pulled from queues
        while (next != null && next.isDone()) {
            kernel.closeAllFor(next);
            kernel.freeAllMemory(next);
            pidMap.remove(next.pid);
            waitingForMessage.remove(next.pid);
            next = chooseNextProcess();
        }

        // If a sleeper leaked into a ready queue, put it back into sleeping
        while (next != null && next.wakeTime > now) {
            removeFromAllQueues(next);
            sleeping.add(next);
            next = chooseNextProcess();
        }

        currentlyRunning = next;
        // Every scheduling path (including sleep, exit, and IPC) changes address spaces.
        Hardware.clearTlb();
    }

    // Put current PCB to sleep, remove from ready, add to sleeping
    public synchronized void sleep(PCB pcb, int millis) {
        pcb.resetTimeouts();
        pcb.wakeTime = System.currentTimeMillis() + Math.max(0, millis);
        if (pcb == currentlyRunning) {
            currentlyRunning = null;
        }
        removeFromAllQueues(pcb);
        sleeping.add(pcb);
        switchProcess();
    }

    // Remove process completely
    public synchronized void removeProcess(PCB pcb) {
        if (pcb == currentlyRunning) currentlyRunning = null;
        kernel.closeAllFor(pcb);
        kernel.freeAllMemory(pcb);
        pidMap.remove(pcb.pid);
        waitingForMessage.remove(pcb.pid);
        removeFromAllQueues(pcb);
        sleeping.remove(pcb);
        pcb.terminate();
        switchProcess();
    }

    // Enqueue by current priority
    private void addToQueue(PCB pcb) {
        switch (pcb.getPriority()) {
            case realtime -> realtime.addLast(pcb);
            case interactive -> interactive.addLast(pcb);
            case background -> background.addLast(pcb);
        }
    }

    // Remove from any ready queue
    private void removeFromAllQueues(PCB pcb) {
        realtime.remove(pcb);
        interactive.remove(pcb);
        background.remove(pcb);
    }

    private PCB pollFromQueue(LinkedList<PCB> q) {
        while (!q.isEmpty()) {
            PCB candidate = q.pollFirst();
            if (candidate != null && !candidate.isDone()) {
                return candidate;
            }
            if (candidate != null && candidate.isDone()) {
                kernel.closeAllFor(candidate);
                kernel.freeAllMemory(candidate);
            }
        }
        return null;
    }

    private PCB chooseNextProcess() {
        PCB next = chooseByPriority();
        if (next != null) return next;
        // A probability branch may choose an empty queue; ready work must still run.
        next = pollFromQueue(realtime);
        if (next == null) next = pollFromQueue(interactive);
        if (next == null) next = pollFromQueue(background);
        return next;
    }

    private PCB chooseByPriority() {
        PCB next = null;
        if (!realtime.isEmpty()) {
            int roll = rand.nextInt(10);
            if (roll < 6) {
                next = pollFromQueue(realtime);
                if (next != null) return next;
                if (!interactive.isEmpty()) {
                    next = pollFromQueue(interactive);
                    if (next != null) return next;
                }
                return pollFromQueue(background);
            } else if (roll < 9) {
                if (!interactive.isEmpty()) {
                    next = pollFromQueue(interactive);
                    if (next != null) return next;
                }
                return pollFromQueue(background);
            } else {
                return pollFromQueue(background);
            }
        } else if (!interactive.isEmpty()) {
            int roll = rand.nextInt(4);
            if (roll < 3) {
                next = pollFromQueue(interactive);
                if (next != null) return next;
                return pollFromQueue(background);
            } else {
                return pollFromQueue(background);
            }
        } else if (!background.isEmpty()) {
            return pollFromQueue(background);
        }
        return null; // nothing runnable
    }

    public synchronized PCB getRandomProcess() {
        if (pidMap.isEmpty()) return null;

        // Build a pool of candidates
        java.util.ArrayList<PCB> pool = new java.util.ArrayList<>(pidMap.values());
        // Prefer not to evict from the currently running process
        if (currentlyRunning != null) pool.remove(currentlyRunning);

        java.util.Collections.shuffle(pool, rand);

        for (PCB p : pool) {
            if (p == null || p.isDone()) continue;
            return p; // Kernel will verify it actually has a resident page
        }
        return null;
    }


    // Lookup by PID
    public synchronized PCB getPcbByPid(int pid) {
        return pidMap.get(pid);
    }

    // Block a process waiting for a message
    public synchronized void blockCurrentWaitingForMessage(PCB pcb) {
        pcb.resetTimeouts();
        if (pcb == currentlyRunning) {
            currentlyRunning = null;
        }
        removeFromAllQueues(pcb);
        sleeping.remove(pcb);
        waitingForMessage.add(pcb.pid);
        switchProcess();
    }

    // Wake a process if it was blocked waiting for a message
    public synchronized void wakeIfWaitingForMessage(PCB pcb) {
        if (waitingForMessage.remove(pcb.pid)) {
            addToQueue(pcb);
        }
    }

    public synchronized int getPidByName(String name) {
        if (name == null) return -1;
        for (PCB pcb : pidMap.values()) {
            if (name.equals(pcb.getName()) && !pcb.isDone()) {
                return pcb.pid;
            }
        }
        return -1;
    }

    static final class Victim {
        final PCB pcb;   // owner of the page
        final int vpn;   // victim virtual page number
        final int ppn;   // victim physical page number
        Victim(PCB pcb, int vpn, int ppn) {
            this.pcb = pcb; this.vpn = vpn; this.ppn = ppn;
        }
    }

    Victim findResidentVictim(PCB exclude) {
        // Build candidate list from the live PCBs
        java.util.List<PCB> procs = new java.util.ArrayList<>(pidMap.values());

        // Prefer not to evict from the currently running process
        if (exclude != null) procs.remove(exclude);

        procs.removeIf(p -> p == null || p.isDone() || "IdleProcess".equals(p.getName()));

        // Randomize process order
        java.util.Collections.shuffle(procs, rand);

        for (PCB p : procs) {
            VirtualtoPhysicalMapping[] pt = p.getPageTable(); // <-- add accessor in PCB
            if (pt == null || pt.length == 0) continue;

            // Randomize page scan
            java.util.List<Integer> vpns = new java.util.ArrayList<>();
            for (int v = 0; v < pt.length; v++) vpns.add(v);
            java.util.Collections.shuffle(vpns, rand);

            for (int v : vpns) {
                VirtualtoPhysicalMapping m = pt[v];
                if (m != null && m.physicalPage >= 0) {
                    return new Victim(p, v, m.physicalPage);
                }
            }
        }
        return null;
    }


}
