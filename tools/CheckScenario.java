import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Checks the original console workloads; JVM shutdown belongs to this test adapter. */
public class CheckScenario {
    private static volatile boolean startupReturned;
    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    private static PrintStream capture(PrintStream original, ByteArrayOutputStream buffer) {
        return new PrintStream(new OutputStream() {
            @Override public synchronized void write(int b) { original.write(b); buffer.write(b); }
            @Override public synchronized void write(byte[] b, int off, int len) {
                original.write(b, off, len); buffer.write(b, off, len);
            }
        }, true);
    }
    private static void require(String output, String text) {
        if (!output.contains(text)) throw new AssertionError("Missing output: " + text);
    }
    private static void check(String name, String output) throws Exception {
        if (output.matches("(?s).*(MISMATCH|mismatch|VERIFY FAIL|allocation failed|alloc failed|THIS SHOULD NOT PRINT|No mapping|Out of physical memory).*"))
            throw new AssertionError("Workload reported failure");
        if (!name.equals("MemoryFaultTest") && !name.equals("InitPagingTests") && output.contains("Segmentation fault"))
            throw new AssertionError("Unexpected memory fault");
        switch (name) {
            case "Main", "InitSwapTests" -> {
                require(output, "[MemSmoke] OK"); require(output, "[MemSmoke] free=true");
                for (int i=1; i<=20; i++) { require(output, "[Piggy"+i+"] verify OK"); require(output, "[Piggy"+i+"] freed=true"); }
                require(output, "[EVICT]");
            }
            case "InitPagingTests" -> { check("MemoryReadWriteTest", output.replaceAll("Segmentation fault[^\\r\\n]*", "")); check("MemoryExtendTest", output.replaceAll("Segmentation fault[^\\r\\n]*", "")); require(output,"at virtual page 50"); }
            case "InitPingPong" -> {
                for(int i=0;i<5;i++) {
                    if (!output.matches("(?s).*PONG: from: \\d+ to: \\d+ what: "+(i*2)+"[\\r\\n].*")) throw new AssertionError("Missing Pong reply "+i);
                    if (!output.matches("(?s).*PING: from: \\d+ to: \\d+ what: "+(i*2+1)+"[\\r\\n].*")) throw new AssertionError("Missing Ping reply "+i);
                }
            }
            case "Init" -> {
                require(output,"open='random'"); require(output,"[-70, 13, -82, 12, 79, -15, 70, -75]");
                check("TestFileWriteRead",output); check("TestOpenCapacity",output);
                require(output,"[File-R1] [1, 2, 3, 4, 5, 6, 7, 8]"); require(output,"[File-R2] [1, 2, 3, 4, 5, 6, 7, 8]");
            }
            case "MemoryReadWriteTest" -> { require(output,"read/write OK"); require(output,"free result = true"); }
            case "MemoryExtendTest" -> { require(output,"both regions OK"); require(output,"free1=true free2=true"); }
            case "MemoryFaultTest" -> require(output,"at virtual page 50");
            case "MemSmoke" -> { require(output,"[MemSmoke] OK"); require(output,"[MemSmoke] free=true"); }
            case "Piggy" -> { require(output,"[Piggy1] verify OK"); require(output,"[Piggy1] freed=true"); }
            case "PIDTest" -> { if (!output.matches("(?s).*PidTester: my PID is [1-9][0-9]*.*")) throw new AssertionError("Invalid PID"); }
            case "SleepTest" -> { require(output,"Sleeping for 1000 ms"); require(output,"Awake"); }
            case "ExitTest" -> require(output,"Now exiting");
            case "HelloWorld" -> require(output,"Hello World");
            case "GoodbyeWorld" -> require(output,"Goodbye World");
            case "TestRandomOnce" -> { require(output,"fd=0"); if (!output.matches("(?s).*\\[Rnd\\] bytes=\\[-?\\d+(, -?\\d+){7}\\].*")) throw new AssertionError("Expected eight random bytes"); }
            case "TestFileWriteRead" -> { require(output,"wrote=4 read=[10, 20, 30, 40]"); if (!Arrays.equals(Files.readAllBytes(Path.of("simple.dat")),new byte[]{10,20,30,40})) throw new AssertionError("File bytes"); }
            case "TestFileSeed" -> { if (!Arrays.equals(Files.readAllBytes(Path.of("shared.dat")),new byte[]{1,2,3,4,5,6,7,8})) throw new AssertionError("Seed bytes"); }
            case "TestFileReader" -> require(output,"[File-R1] []"); // Fresh, deliberately unseeded fixture.
            case "TestOpenCapacity" -> require(output,"[Cap] opened=9 failed=3");
            default -> throw new AssertionError("Unknown scenario: "+name);
        }
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(), errors=new ByteArrayOutputStream();
        System.setOut(capture(System.out,output)); System.setErr(capture(System.err,errors));
        Thread monitor=new Thread(() -> {
            try {
                for (;;) {
                    Thread.sleep(25);
                    if (errors.size()>0) throw new AssertionError("Scenario emitted stderr");
                    String text=output.toString();
                    if(text.matches("(?s).*(MISMATCH|mismatch|VERIFY FAIL|THIS SHOULD NOT PRINT).*")) throw new AssertionError("Scenario reported failure");
                    if (!startupReturned) continue;
                    Kernel k=(Kernel)field(null,OS.class,"ki");
                    Scheduler s=(Scheduler)field(k,Kernel.class,"scheduler");
                    synchronized(s) {
                        Map<?,?> pids=(Map<?,?>)field(s,Scheduler.class,"pidMap");
                        if(pids.values().stream().anyMatch(p -> !((PCB)p).getName().equals("IdleProcess"))) continue;
                    }
                    if (Boolean.getBoolean("verify.threadCleanup")) {
                        boolean remaining=false;
                        for(var entry:Thread.getAllStackTraces().entrySet()) {
                            if(!entry.getKey().getName().startsWith("Process-")) continue;
                            boolean service=Arrays.stream(entry.getValue()).anyMatch(f -> f.getClassName().equals("Kernel") || f.getClassName().equals("IdleProcess"));
                            if(!service) remaining=true;
                        }
                        if(remaining) continue;
                    }
                    // Take the output snapshot after observing workload completion.
                    text=output.toString();
                    if(errors.size()>0) throw new AssertionError("Scenario emitted stderr");
                    check(args[0],text);
                    if(args.length>1 && args[0].equals("TestRandomOnce") && args[1].equals("random 42")) require(text,"[-70, 13, -82, 12, 79, -15, 70, -75]");
                    System.out.println("SCENARIO PASS: "+args[0]); System.exit(0);
                }
            } catch(Throwable e) { e.printStackTrace(); System.exit(1); }
        },"Scenario-monitor");
        monitor.setDaemon(true); monitor.start();
        if(args[0].equals("Main")) Main.main(new String[0]); else RunScenario.main(args);
        startupReturned=true;
    }
}
