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
		super.selfTest();
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
			swapIndex = 0;
			swapPages = new LinkedList<Integer>();
			int physPages = Machine.processor().getNumPhysPages();
			this.ipt = new IPT(physPages);
		}
		
		public IPT getIPT() {
			return ipt;
		}
		
		public boolean hasSwapPage(TranslationEntry entry) {
			int spn = entry.ppn;
			return spn >= 0 && spn < swapIndex && !entry.valid && !entry.readOnly;
		}
		
		public void readSwap(TranslationEntry entry) {
			swapPages.add(entry.ppn);
			int pos = entry.ppn * Processor.pageSize;
			byte[] memory = Machine.processor().getMemory();
			swapperinos.read(pos, memory, entry.ppn, Processor.pageSize);
			Machine.stats.numSwapReads++;
		}
		
		public void writeSwap(TranslationEntry entry) {
			if (swapPages.isEmpty())
				swapPages.add(swapIndex++);
			entry.ppn = swapPages.removeFirst();
			int pos = entry.ppn * Processor.pageSize;
			byte[] memory = Machine.processor().getMemory();
			swapperinos.write(pos, memory, entry.ppn, Processor.pageSize);
			Machine.stats.numSwapWrites++;
		}
		
		public void close() {
			swapperinos.close();
			ThreadedKernel.fileSystem.remove("swapperinos");
		}
		private OpenFile swapperinos;
		private int swapIndex;
		private LinkedList<Integer> swapPages;
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
		
		public int getPPN() { // TODO: handle pinned pages return -1 -> condition.sleep();
			if (!VMProcess.freePages.isEmpty())
				return VMProcess.freePages.removeFirst().index;
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
