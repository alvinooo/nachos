package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import nachos.vm.VMKernel.IPT;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		lastReplaced = -1;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
		// Flush TLB
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {

		// Initialize page table
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++)
			pageTable[i] = new TranslationEntry(i, -1, false, false, false,
					false);

		// Synchronize access to free pages and main memory
		memlock.acquire();

		// Keep track of vpns for each section
		int pages = 0;
		for (int s = 0; s < coff.getNumSections(); s++)
			pages += coff.getSection(s).getLength();
		coffPages = new CoffPage[pages];

		// Load coff entries
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			// Load section number
			for (int i = 0; i < section.getLength(); i++) {
				Machine.stats.numCOFFReads++;
				int vpn = section.getFirstVPN() + i;
				coffPages[vpn] = new CoffPage(s, i);
				pageTable[vpn] = new TranslationEntry(vpn, 0, false, section.isReadOnly(), false, false);
				boolean swapped = false;
				if (!section.isReadOnly()) {
					section.loadPage(i, pageTable[vpn].ppn);
					VMKernel.swapper.writeSwap(pageTable[vpn]);
					swapped = true;
				}
				if (!swapped)
					pageTable[vpn].ppn = -1;
			}
		}

		memlock.release();
		for (int i = 0; i < numPages; i++)
			System.out.println("PTE vpn: " + pageTable[i].vpn + " ppn: " + pageTable[i].ppn + " valid: " + pageTable[i].valid + " readOnly: " + pageTable[i].readOnly + " dirty: " + pageTable[i].dirty);


		processLock.acquire();
		numProcesses++;
		processLock.release();

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionTLBMiss:
			handleTLBMiss(processor);
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	private void handleTLBMiss(Processor processor) {
		
		// Get the virtual page number
		int vpn = Processor.pageFromAddress(processor
				.readRegister(Processor.regBadVAddr));

		// Find a TLB entry to evict
		boolean found = false;
		int TLBsize = processor.getTLBSize();
		for (int i = 0; i < TLBsize; i++) {
			TranslationEntry entry = processor.readTLBEntry(i);

			// Look for an empty slot or page not in physical memory
			if (!entry.valid) {
				
				found = true;

				// Select eviction point
				lastReplaced = i;
				break;
			}
		}

		// Otherwise evict a page you haven't just brought in
		if (!found)
			lastReplaced = (lastReplaced + 1) % TLBsize;
		
		// Sync w/ page table
		TranslationEntry entry = processor.readTLBEntry(lastReplaced);
		if (entry.dirty || entry.used) {
			pageTable[entry.vpn] = new TranslationEntry(entry); // TODO: get process
		}
		
		// Find a page to bring in
		TranslationEntry replacement = pageTable[vpn];
		if (!pageTable[vpn].valid)
			replacement = handlePageFault(vpn);
		processor.writeTLBEntry(lastReplaced, replacement);
	}

	/** Index of last page brought into TLB */
	private int lastReplaced;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
