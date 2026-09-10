public class Hardware {
    public static final int PAGE_SIZE = 1024;      // 1 KB
    public static final int NUM_PAGES = 1024;      // 1 MB total
    private static final int MEM_SIZE = PAGE_SIZE * NUM_PAGES;

    // Simulated physical memory: 1,048,576 bytes
    private static final byte[] memory = new byte[MEM_SIZE];

    private static final int[][] tlb = new int[2][2];

    static {
        // Mark TLB entries as invalid
        for (int i = 0; i < 2; i++) {
            tlb[i][0] = -1;
            tlb[i][1] = -1;
        }
    }

    // Simulated load from virtual address
    public static byte Read(int address) {
        int phys = translate(address);
        if (phys < 0 || phys >= MEM_SIZE) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        return memory[phys];
    }

    // Simulated store to virtual address
    public static void Write(int address, byte value) {
        int phys = translate(address);
        if (phys < 0 || phys >= MEM_SIZE) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        memory[phys] = value;
    }


    // Translate virtual byte address using TLB
    private static int translate(int virtualAddress) {
        if (virtualAddress < 0) {
            throw new IllegalArgumentException("Negative virtual address: " + virtualAddress);
        }

        int virtualPage = virtualAddress / PAGE_SIZE;
        int offset      = virtualAddress % PAGE_SIZE;

        int physicalPage = lookupTlb(virtualPage);
        if (physicalPage < 0) {
            OS.GetMapping(virtualPage);
            physicalPage = lookupTlb(virtualPage);
            if (physicalPage < 0) {
                throw new IllegalStateException("No mapping for virtual page " + virtualPage);
            }
        }

        return physicalPage * PAGE_SIZE + offset;
    }

    // Return physical page for a virtual page or -1 if not in TLB
    private static int lookupTlb(int virtualPage) {
        for (int i = 0; i < 2; i++) {
            if (tlb[i][0] == virtualPage) {
                return tlb[i][1];
            }
        }
        return -1;
    }

    // Kernel uses this to program a TLB slot
    public static void setTlbEntry(int slot, int virtualPage, int physicalPage) {
        if (slot < 0 || slot >= 2) {
            throw new IllegalArgumentException("TLB slot out of range: " + slot);
        }
        // Replacing a mapping must not leave a second, older translation behind.
        for (int i = 0; i < 2; i++) {
            if (tlb[i][0] == virtualPage) {
                tlb[i][0] = -1;
                tlb[i][1] = -1;
            }
        }
        tlb[slot][0] = virtualPage;
        tlb[slot][1] = physicalPage;
    }

    // Called on context switch
    public static void clearTlb() {
        for (int i = 0; i < 2; i++) {
            tlb[i][0] = -1;
            tlb[i][1] = -1;
        }
    }

    public static void readPhysicalPage(int ppn, byte[] dst) {
        if (ppn < 0 || ppn >= NUM_PAGES || dst == null || dst.length < PAGE_SIZE)
            throw new IllegalArgumentException("readPhysicalPage args");
        int base = ppn * PAGE_SIZE;
        System.arraycopy(memory, base, dst, 0, PAGE_SIZE);
    }

    public static void writePhysicalPage(int ppn, byte[] src) {
        if (ppn < 0 || ppn >= NUM_PAGES || src == null || src.length < PAGE_SIZE)
            throw new IllegalArgumentException("writePhysicalPage args");
        int base = ppn * PAGE_SIZE;
        System.arraycopy(src, 0, memory, base, PAGE_SIZE);
    }

    public static void zeroPhysicalPage(int ppn) {
        if (ppn < 0 || ppn >= NUM_PAGES)
            throw new IllegalArgumentException("zeroPhysicalPage arg");
        int base = ppn * PAGE_SIZE;
        java.util.Arrays.fill(memory, base, base + PAGE_SIZE, (byte) 0);
    }

}
