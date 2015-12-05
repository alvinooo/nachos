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
		swapLock = new Lock();
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		// super.selfTest();
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
		swapper.close();
		super.terminate();
	}

	public class Swapper {
		public Swapper() {
			// Remove pre-existing swap file
			swapperinos = ThreadedKernel.fileSystem.open("swapperinos", true);
			close();
			swapperinos = ThreadedKernel.fileSystem.open("swapperinos", true);
			swapPages = new LinkedList<Boolean>();
			this.ipt = new IPT(Machine.processor().getNumPhysPages());
		}

		public IPT getIPT() {
			return ipt;
		}

		public boolean inSwapFile(TranslationEntry entry, int[] spns) {
			return !entry.valid && spns[entry.vpn] >= 0;
		}

		public void readSwap(int spn, int ppn) {
			swapLock.acquire();
			swapPages.set(spn, true);
			byte[] memory = Machine.processor().getMemory();
			swapperinos.read(spn * Processor.pageSize, memory, ppn
					* Processor.pageSize, Processor.pageSize);
			swapLock.release();
		}

		public int writeSwap(int ppn) {

			swapLock.acquire();
			int pos;
			int size = swapPages.size();

			// Search for an open page
			for (pos = 0; pos < size; pos++) {
				if (swapPages.get(pos)) {
					swapPages.set(pos, false);
					break;
				}
			}

			// Grow swap file if necessary
			if (pos == size)
				swapPages.add(false);

			// Write to swap file
			byte[] memory = Machine.processor().getMemory();
			swapperinos.write(pos * Processor.pageSize, memory, ppn
					* Processor.pageSize, Processor.pageSize);
			swapLock.release();
			return pos;
		}

		public void close() {
			swapperinos.close();
			ThreadedKernel.fileSystem.remove("swapperinos");
		}

		private OpenFile swapperinos;
		private LinkedList<Boolean> swapPages; // Keeps track of which pages are
												// free
		private IPT ipt;
	}

	public class IPT {

		public IPT(int size) {
			victim = 0;
			pages = new PageFrame[size];
			for (int i = 0; i < size; i++)
				pages[i] = new PageFrame(null, new TranslationEntry(0, 0, false, false, false, false));
			pageLock = new Lock();
			pinnedPages = 0;
			pinCountLock = new Lock();
			pinLock = new Lock();
			canPin = new Condition(pinLock);
		}

		public void update(int ppn, VMProcess process, TranslationEntry entry) {
			pageLock.acquire();
			pages[ppn] = new PageFrame(process, entry);
			pageLock.release();
		}

		public PageFrame getPage(int ppn) {
			pageLock.acquire();
			if (ppn < 0 || ppn >= pages.length) {
				pageLock.release();
				return null;
			}
			pageLock.release();
			return pages[ppn];
		}

		public VMProcess getProcess(int ppn) {
			return pages[ppn].process;
		}

		public int getVPN(int ppn) {
			return pages[ppn].entry.vpn;
		}

		public int getPPN() {
			pinLock.acquire();
			while (pinnedPages >= Machine.processor().getNumPhysPages())
				canPin.sleep();
			if (!VMKernel.freePages.isEmpty()) {
				pinLock.release();
				return (int) VMKernel.freePages.removeFirst();
			}
			while (pages[victim].entry.used && victim < pages.length - 1
					&& pages[victim].pinCount == 0) {
				pages[victim].entry.used = false;
				victim = (victim + 1) % pages.length;
			}
			int evict = victim;
			victim = (victim + 1) % pages.length;
			pinLock.release();
			return evict;
		}
		
		public void pin(int ppn) {
			pinCountLock.acquire();
			getPage(ppn).pinCount++;
			pinnedPages++;
			pinCountLock.release();
		}
		
		public void unpin(int ppn) {
			pinLock.acquire();
			PageFrame page = getPage(ppn);
			if (page != null && page.pinCount > 0) {
				pinCountLock.acquire();
				getPage(ppn).pinCount--;
				pinCountLock.release();
				if (getPage(ppn).pinCount == 0) {
					pinnedPages--;
					canPin.wake();
				}
			}
			pinLock.release();
		}

		private int victim;
		private Lock pageLock;
		private PageFrame[] pages;
		private int pinnedPages;
		private Lock pinCountLock;
		private Lock pinLock;
		private Condition canPin;
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

	public static Lock swapLock;

	public static Swapper swapper;

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
}
