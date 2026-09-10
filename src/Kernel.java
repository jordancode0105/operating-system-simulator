import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Kernel extends Process implements Device {

    private final ConcurrentLinkedQueue<OS.SystemCall> calls = new ConcurrentLinkedQueue<>();
    private final Scheduler scheduler = new Scheduler(this);
    private final VFS vfs = new VFS();
    private final boolean[] physicalPageUsed = new boolean[Hardware.NUM_PAGES];
    private final Random rand = new Random();
    private int swapVfsId = -1;
    private int nextSwapPage = 0;

    public Kernel() {
        swapVfsId = vfs.Open("file swap.bin");
        if (swapVfsId < 0) {
            throw new IllegalStateException("Failed to open swap file");
        }
    }

    void bootstrap(UserlandProcess init) {
        synchronized (scheduler) {
            scheduler.createProcess(init, OS.PriorityType.interactive);
            scheduler.createProcess(new IdleProcess(), OS.PriorityType.background);
        }
    }

    void submit(OS.SystemCall call) {
        calls.add(call);
        start();
    }

    void requestDispatch() {
        submit(new OS.SystemCall(OS.CallType.SwitchProcess, null));
    }

    @Override
    public void main() {
        PCB initial = scheduler.getCurrentlyRunning();
        if (initial != null) initial.start();
        while (true) {
            // Kernel sleeps until the next syscall.
            this.stop();
            OS.SystemCall call = calls.poll();
            if (call == null) continue;
            synchronized (scheduler) {
                if (call.caller == null) {
                    // Timer wakeups must never release an already executing process's gate.
                    if (scheduler.getCurrentlyRunning() == null) {
                        scheduler.switchProcess();
                        PCB ready = scheduler.getCurrentlyRunning();
                        if (ready != null) ready.start();
                    }
                    continue;
                }
                OS.currentCall = call.type;
                OS.parameters = new java.util.ArrayList<>(call.arguments);
                try {
                    switch (call.type) {
                        case CreateProcess -> call.result =
                                CreateProcess((UserlandProcess) call.arguments.get(0),
                                        (OS.PriorityType) call.arguments.get(1));
                        case SwitchProcess -> SwitchProcess();
                        case GetPID -> call.result = GetPid();
                        case Exit   -> Exit();
                        case Sleep  -> Sleep((int) call.arguments.get(0));
                        case Open -> call.result = Open((String) call.arguments.get(0));
                        case Close -> Close((int) call.arguments.get(0));
                        case Read -> call.result = Read((int) call.arguments.get(0),
                                (int) call.arguments.get(1));
                        case Seek -> Seek((int) call.arguments.get(0),
                                (int) call.arguments.get(1));
                        case Write -> call.result = Write((int) call.arguments.get(0),
                                (byte[]) call.arguments.get(1));
                        case SendMessage -> SendMessage((KernelMessage) call.arguments.get(0));
                        case WaitForMessage -> call.result = WaitForMessage();
                        case GetPIDByName -> call.result = GetPidByName((String) call.arguments.get(0));
                        case GetMapping -> GetMapping((int) call.arguments.get(0));
                        case AllocateMemory -> call.result = AllocateMemory((int) call.arguments.get(0));
                        case FreeMemory -> call.result = FreeMemory(
                                (int) call.arguments.get(0),
                                (int) call.arguments.get(1)
                        );

                    }

                } catch (RuntimeException | Error failure) {
                    call.failure = failure;
                }
                OS.retVal = call.result;
                call.completed.release();
                PCB toRun = scheduler.getCurrentlyRunning();
                if (toRun != null) toRun.start();
            }
        }
    }

    private void SwitchProcess() {
        scheduler.switchProcess();
    }

    public PCB getCurrentlyRunning() {
        return scheduler.getCurrentlyRunning();
    }

    boolean isCurrentProcess(Process process) {
        PCB current = scheduler.getCurrentlyRunning();
        return current != null && current.owns(process);
    }

    private int CreateProcess(UserlandProcess up, OS.PriorityType priority) {
        return scheduler.createProcess(up, priority);
    }

    private void Sleep(int millis) {
        PCB current = scheduler.getCurrentlyRunning();
        if (current != null) {
            scheduler.sleep(current, millis);
        }
    }

    private void Exit() {
        // Remove current from all scheduler structures and close its resources
        PCB current = scheduler.getCurrentlyRunning();
        if (current != null) {
            scheduler.removeProcess(current);
        }
    }

    private int GetPid() {
        PCB current = scheduler.getCurrentlyRunning();
        return (current != null) ? current.pid : -1;
    }


    private PCB currentPcb() {
        return scheduler.getCurrentlyRunning();
    }

    @Override
    public int Open(String s) {
        PCB pcb = currentPcb();
        if (pcb == null) return -1;

        // Find a free per-process fd slot
        int fd = pcb.firstFreeFd();
        if (fd == -1) return -1;

        // Open in VFS, store returned VFS id into per-process table
        int vfsId = vfs.Open(s);
        if (vfsId < 0) return -1;

        pcb.setFd(fd, vfsId);
        return fd;
    }

    @Override
    public void Close(int id) {
        PCB pcb = currentPcb();
        if (pcb == null) return;

        int vfsId = pcb.getFd(id);
        if (vfsId < 0) return;

        vfs.Close(vfsId);
        pcb.setFd(id, -1);
    }

    @Override
    public byte[] Read(int id, int size) {
        PCB pcb = currentPcb();
        if (pcb == null) return new byte[0];

        int vfsId = pcb.getFd(id);
        if (vfsId < 0) return new byte[0];

        return vfs.Read(vfsId, size);
    }

    @Override
    public void Seek(int id, int to) {
        PCB pcb = currentPcb();
        if (pcb == null) return;

        int vfsId = pcb.getFd(id);
        if (vfsId < 0) return;

        vfs.Seek(vfsId, to);
    }

    @Override
    public int Write(int id, byte[] data) {
        PCB pcb = currentPcb();
        if (pcb == null) return 0;

        int vfsId = pcb.getFd(id);
        if (vfsId < 0) return 0;

        return vfs.Write(vfsId, data);
    }

    // Close every open descriptor for a process
    public void closeAllFor(PCB pcb) {
        if (pcb == null) return;
        int[] fds = pcb.getAllFds();
        for (int i = 0; i < fds.length; i++) {
            if (fds[i] >= 0) {
                vfs.Close(fds[i]);
                pcb.setFd(i, -1);
            }
        }
    }

    private void SendMessage(KernelMessage kmIn) {
        PCB sender = scheduler.getCurrentlyRunning();
        int fromPid = (sender == null) ? -1 : sender.pid;

        KernelMessage km = new KernelMessage(fromPid, kmIn.toPid, kmIn.purpose, kmIn.data);

        // Route to target PCB via scheduler’s PID map
        PCB target = scheduler.getPcbByPid(km.toPid);
        if (target == null) {
            return; // drop if target not found
        }

        target.enqueueMessage(km);

        // If the target was blocked in WaitForMessage, make it runnable again
        scheduler.wakeIfWaitingForMessage(target);
    }

    private KernelMessage WaitForMessage() {
        PCB me = scheduler.getCurrentlyRunning();
        if (me == null) return null;

        KernelMessage msg = me.pollMessage();
        if (msg != null) {
            return msg;
        }

        scheduler.blockCurrentWaitingForMessage(me);

        // The caller retries its own WaitForMessage request after being rescheduled.
        return null;
    }

    private int GetPidByName(String name) {
        // Delegate to scheduler’s PID-by-name scan
        return scheduler.getPidByName(name);
    }

    private void GetMapping(int virtualPage) {
        // Who is making the request?
        PCB me = scheduler.getCurrentlyRunning();
        if (me == null) return;

        // Look up this page's mapping entry
        VirtualtoPhysicalMapping m = me.getMapping(virtualPage);
        if (m == null) {
            System.out.println("Segmentation fault in pid " + me.pid + " at virtual page " + virtualPage);
            scheduler.removeProcess(me);
            return;
        }

        // If the page is not resident, bring it into RAM
        if (m.physicalPage < 0) {
            int ppn = findFreePhysicalPage();

            if (ppn < 0) {
                // No free frames: pick a resident victim from another process
                Scheduler.Victim vic = scheduler.findResidentVictim(me);
                if (vic == null) {
                    System.out.println("Out of physical memory and no victim available");
                    scheduler.removeProcess(me);
                    return;
                }

                // Victim's mapping
                VirtualtoPhysicalMapping vmap = vic.pcb.getMapping(vic.vpn);

                // Ensure the victim has swap backing, write the physical page to swap
                if (vmap.diskPage < 0) {
                    vmap.diskPage = allocSwapPage();
                }
                byte[] pageBuf = new byte[Hardware.PAGE_SIZE];
                Hardware.readPhysicalPage(vic.ppn, pageBuf);
                writeSwapPage(vmap.diskPage, pageBuf);

                // Debug info
                System.out.println("[EVICT] victim pid=" + vic.pcb.pid +
                        " vpn=" + vic.vpn + " -> ppn=" + vic.ppn +
                        " (to disk page " + vmap.diskPage + ")");

                // Steal the frame and mark the victim non-resident
                Hardware.clearTlb();
                ppn = vic.ppn;
                vmap.physicalPage = -1;
            }

            m.physicalPage = ppn;
            physicalPageUsed[ppn] = true;

            if (m.diskPage >= 0) {
                // Page had backing on disk: swap in
                byte[] buf = new byte[Hardware.PAGE_SIZE];
                readSwapPage(m.diskPage, buf);
                Hardware.writePhysicalPage(ppn, buf);
            } else {
                // First touch: zero-fill the new physical frame
                Hardware.zeroPhysicalPage(ppn);
            }
        }
        Hardware.setTlbEntry(rand.nextInt(2), virtualPage, m.physicalPage);
    }



    private int findFreePhysicalPage() {
        for (int i = 0; i < physicalPageUsed.length; i++) {
            if (!physicalPageUsed[i]) {
                return i;
            }
        }
        return -1;
    }

    private int AllocateMemory(int size) {
        PCB current = scheduler.getCurrentlyRunning();
        if (current == null) return -1;
        if (size <= 0 || size % Hardware.PAGE_SIZE != 0) return -1;
        int pagesNeeded = size / Hardware.PAGE_SIZE;
        int ptSize = current.pageTableSize();
        int startVpn = -1;
        outer:
        for (int i = 0; i <= ptSize - pagesNeeded; i++) {
            for (int j = 0; j < pagesNeeded; j++) {
                if (current.getMapping(i + j) != null) { // already allocated
                    continue outer;
                }
            }
            startVpn = i;
            break;
        }
        if (startVpn == -1) {
            return -1; // no contiguous virtual space
        }

        for (int k = 0; k < pagesNeeded; k++) {
            int vpn = startVpn + k;
            current.setMapping(vpn, new VirtualtoPhysicalMapping());
        }


        return startVpn * Hardware.PAGE_SIZE; // starting virtual byte address
    }


    private boolean FreeMemory(int pointer, int size) {
        PCB current = scheduler.getCurrentlyRunning();
        if (current == null) return false;

        if (pointer < 0 || size <= 0 ||
                pointer % Hardware.PAGE_SIZE != 0 || size % Hardware.PAGE_SIZE != 0) {
            return false;
        }

        int startVpn = pointer / Hardware.PAGE_SIZE;
        int pages = size / Hardware.PAGE_SIZE;

        boolean ok = true;
        VirtualtoPhysicalMapping[] pt = current.getPageTable();

        for (int i = 0; i < pages; i++) {
            int vpn = startVpn + i;
            VirtualtoPhysicalMapping m = (vpn >= 0 && vpn < pt.length) ? pt[vpn] : null;
            if (m == null) {
                ok = false;                 // freeing unallocated page
                continue;
            }
            if (m.physicalPage >= 0) {      // only free RAM if resident
                physicalPageUsed[m.physicalPage] = false;
            }
            pt[vpn] = null;                 // free the V->P mapping itself
        }
        Hardware.clearTlb();
        return ok;
    }


    public void freeAllMemory(PCB pcb) {
        if (pcb == null) return;
        VirtualtoPhysicalMapping[] pt = pcb.getPageTable();
        for (int vpn = 0; vpn < pt.length; vpn++) {
            VirtualtoPhysicalMapping m = pt[vpn];
            if (m == null) continue;
            if (m.physicalPage >= 0) {
                physicalPageUsed[m.physicalPage] = false;
            }
            pt[vpn] = null;
        }
        Hardware.clearTlb();
    }


    // Allocate the next slot in the swap file
    private int allocSwapPage() {
        return nextSwapPage++;
    }

    // Seek to a swap page
    private void seekSwapPage(int swapPageIndex) {
        vfs.Seek(swapVfsId, swapPageIndex * Hardware.PAGE_SIZE);
    }

    private void writeSwapPage(int swapPage, byte[] data) {
        seekSwapPage(swapPage);
        vfs.Write(swapVfsId, data);
    }

    private void readSwapPage(int swapPage, byte[] buf) {
        seekSwapPage(swapPage);
        byte[] in = vfs.Read(swapVfsId, Hardware.PAGE_SIZE);
        System.arraycopy(in, 0, buf, 0, Math.min(in.length, buf.length));
    }

}
