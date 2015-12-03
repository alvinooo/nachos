package nachos.vm;

import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
		int numPhysPages = Machine.processor().getNumPhysPages();
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		swapper = new Swapper();
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		//super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		//swapper.close();
		super.terminate();
	}
	
	public class Swapper {
		public Swapper() {
			swapperinos = ThreadedKernel.fileSystem.open("swapperinos", true); // TODO: remove later
			close();
			swapperinos = ThreadedKernel.fileSystem.open("swapperinos", true);
			swapPages = new LinkedList<Boolean>();
			this.ipt = new IPT(Machine.processor().getNumPhysPages());
		}
		
		public IPT getIPT() {
			return ipt;
		}
		
		public boolean inSwapFile(TranslationEntry entry, int [] spns) {
			return !entry.valid && spns[entry.vpn] >= 0;
		}
		
		public void readSwap(int spn, int ppn) {
			swapPages.set(spn, true);
			byte[] memory = Machine.processor().getMemory();
			swapperinos.read(spn * Processor.pageSize, memory, ppn * Processor.pageSize, Processor.pageSize);
			Machine.stats.numSwapReads++;
		}
		
		public int writeSwap(int ppn) {Machine.stats.numSwapWrites++;
			int pos;
			int size = swapPages.size();
			
			// Search for an open page
			for (pos = 0; pos < size; pos++) {
				if (swapPages.get(pos))
				{
					swapPages.set(pos, false);
					break;
				}
			}
			
			// Grow swap file if necessary
			if (pos == size)
				swapPages.add(false);
			
			// Write to swap file
			byte[] memory = Machine.processor().getMemory();
			swapperinos.write(pos * Processor.pageSize, memory, ppn * Processor.pageSize, Processor.pageSize);
			return pos;
		}
		
		public void close() {
			swapperinos.close();
			ThreadedKernel.fileSystem.remove("swapperinos");
		}
		private OpenFile swapperinos;
		private LinkedList<Boolean> swapPages; // Keeps track of which pages are free
		private IPT ipt;
	}

	public class IPT {

		public IPT(int size) {
			victim = 0;
			pages = new PageFrame[size];
		}
		
		public void update(int ppn, VMProcess process, TranslationEntry entry) {
			pages[ppn] = new PageFrame(process, entry);
		}
		
		public PageFrame getPage(int ppn) {
			if (ppn < 0 || ppn >= pages.length)
				return null;
			return pages[ppn];
		}
		
		public VMProcess getProcess(int ppn) {
			return pages[ppn].process;
		}
		
		public int getVPN(int ppn) {
			return pages[ppn].entry.vpn;
		}
		
		public int getPPN() { // TODO: handle pinned pages -> call condition.sleep() on the process, call wake when number of pinned pages <
			if (!VMKernel.freePages.isEmpty())
				return (int) VMKernel.freePages.removeFirst();
			while (pages[victim].entry.used && victim < pages.length - 1) {
				pages[victim].entry.used = false;
				victim = (victim + 1) % pages.length;
			}
			int evict = victim;
			victim = (victim + 1) % pages.length;
			return evict;
		}
		
		private int victim;
		private PageFrame[] pages;
	}

	public class PageFrame {
		public PageFrame(VMProcess process, TranslationEntry entry) {
			this.process = process;
			this.entry = new TranslationEntry(entry);
			this.pinCount = 0;
		}

		public VMProcess process;
		public TranslationEntry entry;
		public int pinCount;

	}
	
	public static Swapper swapper;

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
}
