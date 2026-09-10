public class MemoryExtendTest extends UserlandProcess {
    @Override
    public void main() {
        int size1 = Hardware.PAGE_SIZE;       // 1 KB
        int size2 = 3 * Hardware.PAGE_SIZE;   // 3 KB

        int base1 = OS.AllocateMemory(size1);
        int base2 = OS.AllocateMemory(size2);

        System.out.println("MemoryExtendTest base1 = " + base1 + ", base2 = " + base2);

        if (base1 < 0 || base2 < 0) {
            System.out.println("MemoryExtendTest allocation failed");
            OS.Exit();
            return;
        }

        // Write different patterns into each region
        for (int i = 0; i < size1; i++) {
            Hardware.Write(base1 + i, (byte) 0x11);
        }
        for (int i = 0; i < size2; i++) {
            Hardware.Write(base2 + i, (byte) 0x22);
        }

        // Verify region 1
        for (int i = 0; i < size1; i++) {
            byte b = Hardware.Read(base1 + i);
            if (b != 0x11) {
                System.out.println("MemoryExtendTest mismatch in region1 at " + i + " got=" + b);
                OS.Exit();
                return;
            }
        }

        // Verify region 2
        for (int i = 0; i < size2; i++) {
            byte b = Hardware.Read(base2 + i);
            if (b != 0x22) {
                System.out.println("MemoryExtendTest mismatch in region2 at " + i + " got=" + b);
                OS.Exit();
                return;
            }
        }

        System.out.println("MemoryExtendTest both regions OK");

        boolean free1 = OS.FreeMemory(base1, size1);
        boolean free2 = OS.FreeMemory(base2, size2);
        System.out.println("MemoryExtendTest free1=" + free1 + " free2=" + free2);

        OS.Exit();
    }
}
