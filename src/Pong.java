public class Pong extends UserlandProcess {
    @Override
    public void main() {
        // Find Ping
        int myPid = OS.GetPID();
        int pingPid = -1;
        while (pingPid < 0) {
            pingPid = OS.GetPidByName("Ping");
            if (pingPid < 0) OS.Sleep(10); // wait for Ping to spawn
        }
        System.out.println("I am PONG, ping = " + pingPid);

        // Wait/respond loop
        for (int i = 0; i < 5; i++) {
            KernelMessage m = OS.WaitForMessage();
            System.out.println("  PONG: from: " + m.fromPid + " to: " + m.toPid + " what: " + m.purpose);
            // reply with purpose+1
            OS.SendMessage(new KernelMessage(-1, pingPid, m.purpose + 1, null));
        }

        OS.Exit();
    }
}
