package nachos.threads;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		long time = Machine.timer().getTime();
	    Iterator<Entry<Long, Semaphore>> it = waitQueue.entrySet().iterator();
	    while (it.hasNext()) {
	    	Entry<Long, Semaphore> entry = it.next();
	    	long wakeTime = entry.getKey();
			if (time >= wakeTime) {
				entry.getValue().V();
				it.remove();
			}
		}
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		long wakeTime = Machine.timer().getTime() + x;
		Semaphore s = new Semaphore(0);
		waitQueue.put(wakeTime, s);
		s.P();
	}
	
	public static void selftest() {
	    KThread t1 = new KThread(new Runnable() {
	        public void run() {
	            long time1 = Machine.timer().getTime();
	            int waitTime = 10000;
	            System.out.println("Thread calling wait at time:" + time1);
	            ThreadedKernel.alarm.waitUntil(waitTime);
	            System.out.println("Thread woken up after:" + (Machine.timer().getTime() - time1));
	            Lib.assertTrue((Machine.timer().getTime() - time1) > waitTime, " thread woke up too early.");
	            
	        }
	    });
	    t1.setName("T1");
	    t1.fork();
	    t1.join();
	}
	
	private HashMap<Long, Semaphore> waitQueue = new HashMap<Long, Semaphore>();
}
