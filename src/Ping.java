public class Ping extends UserlandProcess {
    @Override
    public void main() {
        // Find Pong
        int myPid = OS.GetPID();
        int pongPid = -1;
        while (pongPid < 0) {
            pongPid = OS.GetPidByName("Pong");
            if (pongPid < 0) OS.Sleep(10); // wait for Pong to spawn
        }
        System.out.println("I am PING, pong = " + pongPid);

        // Start exchange: purpose starts at 0
        OS.SendMessage(new KernelMessage(-1, pongPid, 0, null));

        // Receiving and echoing back with purpose+1 a few times
        for (int i = 0; i < 5; i++) {
            KernelMessage m = OS.WaitForMessage();
            System.out.println("  PING: from: " + m.fromPid + " to: " + m.toPid + " what: " + m.purpose);
            // reply
            OS.SendMessage(new KernelMessage(-1, pongPid, m.purpose + 1, null));
        }

        OS.Exit();
    }
}
