public class MemoryReadWriteTest extends UserlandProcess {
    @Override
    public void main() {
        int size = 2 * Hardware.PAGE_SIZE; // 2 KB
        int base = OS.AllocateMemory(size);
        System.out.println("MemoryReadWriteTest base = " + base);
        if (base < 0) {
            System.out.println("MemoryReadWriteTest allocation failed");
            OS.Exit();
            return;
        }

        // Write pattern
        for (int i = 0; i < size; i++) {
            byte val = (byte) (i % 128);
            Hardware.Write(base + i, val);
        }

        // Read back and verify
        for (int i = 0; i < size; i++) {
            byte expected = (byte) (i % 128);
            byte actual = Hardware.Read(base + i);
            if (actual != expected) {
                System.out.println("MemoryReadWriteTest mismatch at " + i +
                        " expected=" + expected + " actual=" + actual);
                OS.Exit();
                return;
            }
        }

        System.out.println("MemoryReadWriteTest read/write OK");
        boolean freed = OS.FreeMemory(base, size);
        System.out.println("MemoryReadWriteTest free result = " + freed);
        OS.Exit();
    }
}
