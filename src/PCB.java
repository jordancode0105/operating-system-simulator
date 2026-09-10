public class PCB {
    private static int nextPid = 1;

    public final int pid;
    private final UserlandProcess ulp;
    private OS.PriorityType priority;

    // Next earliest wall-clock time this PCB may run (0 = not sleeping)
    long wakeTime = 0L;

    // For demotion: count consecutive timer preemptions
    private int consecutiveTimeouts = 0;

    // Per-process mapping: user fd index -> VFS id (-1 = free)
    private final int[] openDeviceIds = new int[10];

    // Cached userland class simple name (for GetPidByName)
    private final String name;

    // Per-process mailbox for IPC
    private final java.util.LinkedList<KernelMessage> mailbox = new java.util.LinkedList<>();

    // Page table: 100 virtual pages, each with (physicalPage, diskPage)
    private final VirtualtoPhysicalMapping[] pageTable = new VirtualtoPhysicalMapping[100];

    PCB(UserlandProcess up, OS.PriorityType priority) {
        this.pid = nextPid++;
        this.ulp = up;
        this.priority = priority;
        this.name = up.getClass().getSimpleName();

        for (int i = 0; i < openDeviceIds.length; i++) {
            openDeviceIds[i] = -1;
        }
    }


    public String getName() { return name; }

    OS.PriorityType getPriority() { return priority; }

    public void setPriority(OS.PriorityType newPriority) { priority = newPriority; }

    public void requestStop() { ulp.requestStop(); }

    // Block the calling user thread until the scheduler resumes it.
    public void stop() {
        ulp.stop();
    }

    public boolean isDone() { return ulp.isDone(); }

    boolean owns(Process process) { return ulp == process; }
    void terminate() { ulp.terminate(); }

    void start() { ulp.start(); }

    boolean isSleeping(long nowMs) { return wakeTime > nowMs; }

    // Demotion helpers
    public void incTimeouts() { consecutiveTimeouts++; }
    public void resetTimeouts() { consecutiveTimeouts = 0; }
    public int getTimeouts() { return consecutiveTimeouts; }

    // FD table helpers
    public int getFd(int idx) {
        return (idx >= 0 && idx < openDeviceIds.length) ? openDeviceIds[idx] : -1;
    }

    public void setFd(int idx, int vfsId) {
        if (idx >= 0 && idx < openDeviceIds.length) {
            openDeviceIds[idx] = vfsId;
        }
    }

    public int firstFreeFd() {
        for (int i = 0; i < openDeviceIds.length; i++) {
            if (openDeviceIds[i] == -1) return i;
        }
        return -1;
    }

    public int[] getAllFds() { return openDeviceIds; }

    // IPC mailbox
    public void enqueueMessage(KernelMessage km) { mailbox.addLast(km); }
    public KernelMessage pollMessage() { return mailbox.pollFirst(); }
    public boolean hasMessage() { return !mailbox.isEmpty(); }
    public int mailboxSize() { return mailbox.size(); }

    // Paging helpers

    public int pageTableSize() { return pageTable.length; }

    public VirtualtoPhysicalMapping[] getPageTable() { return pageTable; }

    public VirtualtoPhysicalMapping getMapping(int virtualPage) {
        return (virtualPage >= 0 && virtualPage < pageTable.length) ? pageTable[virtualPage] : null;
    }

    public int getPhysicalPage(int virtualPage) {
        VirtualtoPhysicalMapping m = getMapping(virtualPage);
        return (m == null) ? -1 : m.physicalPage;
    }

    public void setPhysicalPage(int virtualPage, int physicalPage) {
        if (virtualPage < 0 || virtualPage >= pageTable.length) return;
        if (pageTable[virtualPage] == null) pageTable[virtualPage] = new VirtualtoPhysicalMapping();
        pageTable[virtualPage].physicalPage = physicalPage;
    }

    public void setMapping(int vpn, VirtualtoPhysicalMapping m) {
        if (vpn >= 0 && vpn < pageTable.length) pageTable[vpn] = m;
    }

    public int getDiskPage(int virtualPage) {
        VirtualtoPhysicalMapping m = getMapping(virtualPage);
        return (m == null) ? -1 : m.diskPage;
    }

    public void setDiskPage(int virtualPage, int diskPage) {
        if (virtualPage < 0 || virtualPage >= pageTable.length) return;
        if (pageTable[virtualPage] == null) pageTable[virtualPage] = new VirtualtoPhysicalMapping();
        pageTable[virtualPage].diskPage = diskPage;
    }
}
