import java.util.concurrent.Semaphore;

public abstract class Process implements Runnable{

    private final Semaphore gate = new Semaphore(0);
    private volatile boolean quantumExpired = false;
    private volatile Thread thread;
    private static final ThreadLocal<Process> executing = new ThreadLocal<>();
    private volatile boolean stopped = true;
    private volatile boolean terminated;
    private volatile boolean finished;

    // Internal control flow for simulated exit/fault, never a user-visible failure.
    static final class ProcessExit extends Error {
        private ProcessExit() { super(null, null, false, false); }
    }

    static Process current() { return executing.get(); }
    void prepareToStop() { stopped = true; }

    public Process() {
    }

    public void requestStop() {
        quantumExpired = true;
    }

    public abstract void main();

    public boolean isStopped() {
        return stopped;
    }

    public boolean isDone() {
        return terminated || finished || (thread != null && !thread.isAlive());
    }

    public synchronized void start() {
        if (isDone()) return;
        if (thread == null) {
            thread = new Thread(this, "Process-" + System.identityHashCode(this));
            thread.start();
        }
        gate.release();
    }

    void terminate() {
        terminated = true;
        gate.release(); // Unwind the exiting caller instead of leaving its thread parked.
    }

    public void stop() {
        stopped = true;
        gate.acquireUninterruptibly();
        if (terminated) throw new ProcessExit();
        stopped = false;
    }

    public void run() {
        executing.set(this);
        try {
            stop();
            main();
        } catch (ProcessExit expected) {
            // OS.Exit and an invalid memory access end only this simulated process.
        }
        finally {
            try {
                if (this instanceof UserlandProcess && !terminated) OS.processFinished(this);
            } finally {
                finished = true;
                stopped = true;
                executing.remove();
            }
        }
    }

    public void cooperate() {
        if (quantumExpired) {
            quantumExpired = false;
            OS.switchProcess();
        }
    }
}
