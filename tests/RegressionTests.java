import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic checks for the coordination, cache, scheduler, and lifecycle bugs. */
public class RegressionTests {
    private static final AtomicInteger finished = new AtomicInteger();
    private static final List<UserlandProcess> workers = new ArrayList<>();
    private static volatile boolean afterExit;
    private static int receiverPid, senderPid;
    private static final AtomicInteger expectedErrors = new AtomicInteger();

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static Object field(Object object, Class<?> type, String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method m=object.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(object,args);
    }
    private static Kernel isolatedKernel() throws Exception {
        Kernel k=new Kernel(); Scheduler s=(Scheduler)field(k,Kernel.class,"scheduler");
        ((Timer)field(s,Scheduler.class,"timer")).cancel(); return k;
    }
    private static int[][] tlb() throws Exception { return (int[][])field(null,Hardware.class,"tlb"); }

    static class StartupWorker extends UserlandProcess {
        int expectedPid;
        public void main() {
            for(int i=0;i<20;i++) {
                check(!isStopped(),"Executing process incorrectly reports itself stopped");
                check(OS.GetPID()==expectedPid,"PID result belongs to another process");
                int fd=OS.Open("random 42");
                check(Arrays.equals(OS.Read(fd,8),new byte[]{-70,13,-82,12,79,-15,70,-75}),"Read result belongs to another call");
                OS.Close(fd);
                int base=OS.AllocateMemory(1024);
                check(base==0 && OS.FreeMemory(base,1024),"Allocation/free result");
                OS.switchProcess();
            }
            finished.incrementAndGet(); OS.Exit();
        }
    }
    static class StartupInit extends UserlandProcess {
        public void main() {
            // A fast Boolean result used to overwrite Startup's PID result.
            int base=OS.AllocateMemory(1024); check(OS.FreeMemory(base,1024),"Initial free");
            for(int i=0;i<24;i++) {
                StartupWorker w=new StartupWorker(); workers.add(w);
                w.expectedPid=OS.CreateProcess(w);
            }
            OS.Exit();
        }
    }
    static class Receiver extends UserlandProcess {
        public void main() {
            for(int i=0;i<40;i++) {
                KernelMessage m=OS.WaitForMessage();
                check(m.purpose==i && m.fromPid==senderPid && m.toPid==receiverPid,"Lost or misrouted IPC reply");
                OS.SendMessage(new KernelMessage(-1,senderPid,i,null));
            }
            finished.incrementAndGet(); OS.Exit();
        }
    }
    static class Sender extends UserlandProcess {
        public void main() {
            OS.Sleep(300); // Receiver blocks first; idle overwrites the old global request type.
            for(int i=0;i<40;i++) {
                OS.SendMessage(new KernelMessage(-1,receiverPid,i,null));
                KernelMessage m=OS.WaitForMessage();
                check(m.purpose==i && m.fromPid==receiverPid,"Lost acknowledgement");
                if((i&7)==0) OS.Sleep(1);
            }
            finished.incrementAndGet(); OS.Exit();
        }
    }
    static class IpcInit extends UserlandProcess {
        public void main() {
            Receiver r=new Receiver(); Sender s=new Sender(); workers.add(r);workers.add(s);
            receiverPid=OS.CreateProcess(r);senderPid=OS.CreateProcess(s); OS.Exit();
        }
    }
    static class MemoryWorker extends UserlandProcess {
        final byte pattern;
        MemoryWorker(int id){pattern=(byte)id;}
        public void main() {
            for(int cycle=0;cycle<3;cycle++) {
                int base=OS.AllocateMemory(4096);check(base==0,"Contiguous virtual allocation");
                for(int p=0;p<4;p++) {
                    check(Hardware.Read(base+p*1024)==0,"Freed frame was not freshly mapped/zeroed");
                    Hardware.Write(base+p*1024,pattern); OS.Sleep(1);
                }
                for(int p=0;p<4;p++) check(Hardware.Read(base+p*1024)==pattern,"Cross-process TLB contamination");
                check(OS.FreeMemory(base,4096),"Free after switches");
            }
            finished.incrementAndGet();OS.Exit();
        }
    }
    static class MemoryInit extends UserlandProcess {
        public void main(){for(int i=1;i<=3;i++){MemoryWorker w=new MemoryWorker(i);workers.add(w);OS.CreateProcess(w);}OS.Exit();}
    }
    static class Swap82Worker extends UserlandProcess {
        final int id;
        Swap82Worker(int id){this.id=id;}
        public void main(){
            int base=OS.AllocateMemory(100*1024);check(base==0,"Swap allocation");
            for(int p=0;p<100;p++){
                Hardware.Write(base+p*1024,(byte)(id^p));
                Hardware.Write(base+p*1024+1023,(byte)~(id^p));
                if((p&15)==0)OS.Sleep(1);
            }
            // Page 82 is always verified, rather than depending on Piggy's random sample.
            for(int i=0;i<10;i++){
                check(Hardware.Read(base+82*1024)==(byte)(id^82),"Page 82 lost its mapping/data");
                OS.Sleep(1);
            }
            for(int p=0;p<100;p++){
                check(Hardware.Read(base+p*1024)==(byte)(id^p),"Swap page start corrupted");
                check(Hardware.Read(base+p*1024+1023)==(byte)~(id^p),"Swap page end corrupted");
            }
            check(OS.FreeMemory(base,100*1024),"Swap free");finished.incrementAndGet();OS.Exit();
        }
    }
    static class Swap82Init extends UserlandProcess {
        public void main(){for(int i=1;i<=20;i++)OS.CreateProcess(new Swap82Worker(i));OS.Exit();}
    }
    static class ExitWorker extends UserlandProcess {
        public void main(){OS.Open("random");OS.AllocateMemory(1024);finished.incrementAndGet();OS.Exit();afterExit=true;}
    }
    static class ReturnWorker extends UserlandProcess {
        public void main(){OS.Open("random");int b=OS.AllocateMemory(1024);Hardware.Write(b,(byte)7);finished.incrementAndGet();}
    }
    static class FaultWorker extends UserlandProcess {
        public void main(){int b=OS.AllocateMemory(1024);Hardware.Write(b,(byte)1);OS.FreeMemory(b,1024);Hardware.Read(b);afterExit=true;}
    }
    static class ExpectedFailure extends RuntimeException {}
    static class ErrorWorker extends UserlandProcess {
        public void main(){OS.Open("random");int b=OS.AllocateMemory(1024);Hardware.Write(b,(byte)9);throw new ExpectedFailure();}
    }
    static class SleepWorker extends UserlandProcess {
        public void main(){long start=System.nanoTime();OS.Sleep(80);check(System.nanoTime()-start>=80_000_000L,"Sleep resumed before its deadline");finished.incrementAndGet();OS.Exit();}
    }
    static class LifecycleInit extends UserlandProcess {
        public void main(){for(int i=0;i<20;i++){UserlandProcess w=i%2==0?new ExitWorker():new ReturnWorker();workers.add(w);OS.CreateProcess(w);}OS.Exit();}
    }

    @SuppressWarnings("unchecked")
    private static void schedulerChecks() throws Exception {
        Kernel k=isolatedKernel();Scheduler s=(Scheduler)field(k,Kernel.class,"scheduler");
        LinkedList<PCB> ready=(LinkedList<PCB>)field(s,Scheduler.class,"interactive");
        PCB p=new PCB(new HelloWorld(),OS.PriorityType.interactive);ready.add(p);
        ((Random)field(s,Scheduler.class,"rand")).setSeed(256);s.switchProcess();
        check(s.getCurrentlyRunning()==p,"Selected empty background queue despite ready interactive process");
        check(p.getTimeouts()==0,"Initial timeout count");
        for(int i=0;i<5;i++)call(s,"tick",new Class[]{});
        check(p.getPriority()==OS.PriorityType.background && p.getTimeouts()==0,"Timer demotion never counted timeouts");
        p.setPriority(OS.PriorityType.realtime);
        for(int i=0;i<5;i++)call(s,"tick",new Class[]{});
        check(p.getPriority()==OS.PriorityType.interactive,"Realtime process was not demoted");
        // Deterministically exercise timer's no-current dispatch through a real kernel,
        // with no idle process installed, rather than relying on bootstrap ordering.
        Kernel wakeKernel=new Kernel(); Scheduler wake=(Scheduler)field(wakeKernel,Kernel.class,"scheduler");
        UserlandProcess resumed=new UserlandProcess(){public void main(){finished.incrementAndGet();}};
        PCB sleeper=new PCB(resumed,OS.PriorityType.interactive);
        synchronized(wake){
            sleeper.wakeTime=System.currentTimeMillis()+50;
            ((PriorityQueue<PCB>)field(wake,Scheduler.class,"sleeping")).add(sleeper);
        }
        wakeKernel.start();
        long deadline=System.nanoTime()+3_000_000_000L;
        while(finished.get()==0 && System.nanoTime()<deadline)Thread.sleep(10);
        check(finished.get()==1,"Woken sleeper was never dispatched without idle");
    }
    private static void cacheChecks() throws Exception {
        Kernel k=isolatedKernel();Scheduler s=(Scheduler)field(k,Kernel.class,"scheduler");
        PCB p=new PCB(new HelloWorld(),OS.PriorityType.interactive);s.currentlyRunning=p;
        call(k,"AllocateMemory",new Class[]{int.class},1024);
        call(k,"GetMapping",new Class[]{int.class},0);Hardware.Write(0,(byte)17);
        call(k,"FreeMemory",new Class[]{int.class,int.class},0,1024);
        check(Arrays.stream(tlb()).noneMatch(row->row[0]==0),"Free left a cached translation");
        Hardware.setTlbEntry(0,0,123);s.sleep(p,1000);
        check(Arrays.stream(tlb()).allMatch(row->row[0]==-1),"Sleep switch left stale TLB");
        Hardware.setTlbEntry(0,0,123);s.removeProcess(p);
        check(Arrays.stream(tlb()).allMatch(row->row[0]==-1),"Exit left stale TLB");
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{
            if(args[0].equals("error-cleanup") && e instanceof ExpectedFailure) {
                expectedErrors.incrementAndGet(); // This case specifically checks uncaught-error cleanup.
            } else { e.printStackTrace(); System.exit(1); }
        });
        String mode=args[0];int expected;
        if(mode.equals("scheduler")){schedulerChecks();System.out.println("REGRESSION PASS: scheduler");System.exit(0);return;}
        if(mode.equals("cache")){cacheChecks();System.out.println("REGRESSION PASS: cache");System.exit(0);return;}
        UserlandProcess init;
        switch(mode){
            case "startup" -> {init=new StartupInit();expected=24;}
            case "ipc" -> {init=new IpcInit();expected=2;}
            case "memory" -> {init=new MemoryInit();expected=3;}
            case "lifecycle" -> {init=new LifecycleInit();expected=20;}
            case "free-fault" -> {init=new FaultWorker();workers.add(init);expected=0;}
            case "error-cleanup" -> {init=new ErrorWorker();workers.add(init);expected=0;}
            case "sleep" -> {init=new SleepWorker();workers.add(init);expected=1;}
            case "swap82" -> {init=new Swap82Init();expected=20;}
            default -> throw new IllegalArgumentException(mode);
        }
        OS.Startup(init);
        // 480 explicit yields may select idle for a full 250 ms quantum each.
        long deadline=System.nanoTime()+90_000_000_000L;
        boolean drained=false;
        while(System.nanoTime()<deadline){
            Thread.sleep(10);
            Kernel k=(Kernel)field(null,OS.class,"ki");Scheduler s=(Scheduler)field(k,Kernel.class,"scheduler");
            synchronized(s){drained=((Map<?,?>)field(s,Scheduler.class,"pidMap")).values().stream().allMatch(p->((PCB)p).getName().equals("IdleProcess"));}
            if(drained && finished.get()==expected)break;
        }
        check(drained && finished.get()==expected,"Incomplete "+mode+": "+finished.get()+"/"+expected);
        check(!afterExit,"Execution continued after exit/fault");
        if(mode.equals("lifecycle")||mode.equals("free-fault")||mode.equals("error-cleanup")){
            for(UserlandProcess w:workers){Thread t=(Thread)field(w,Process.class,"thread");check(t!=null,"Worker never started");t.join(1000);check(!t.isAlive(),"Exited process thread leaked");}
            Kernel k=(Kernel)field(null,OS.class,"ki");
            for(boolean used:(boolean[])field(k,Kernel.class,"physicalPageUsed"))check(!used,"Physical page leaked");
            VFS vfs=(VFS)field(k,Kernel.class,"vfs");
            check(Arrays.stream((Device[])field(vfs,VFS.class,"vfsDevice")).filter(Objects::nonNull).count()==1,"Device descriptors leaked");
        }
        if(mode.equals("error-cleanup"))check(expectedErrors.get()==1,"Uncaught exception was swallowed");
        if(mode.equals("swap82"))check(new java.io.File("swap.bin").length()>0,"Swap test did not evict pages");
        System.out.println("REGRESSION PASS: "+mode);System.exit(0);
    }
}
