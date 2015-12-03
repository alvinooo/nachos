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
		Processor processor = Machine.processor();
		int TLBsize = processor.getTLBSize();
		for (int i = 0; i < TLBsize; i++) {
			TranslationEntry entry = new TranslationEntry();
			entry.valid = false;
			processor.writeTLBEntry(i, entry);
		}
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

		int code = 0;
		int data = 0;
		
		// Initialize page table
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++)
			pageTable[i] = new TranslationEntry(i, -1, false, false, false,
					false);

		// Synchronize access to free pages and main memory
		VMKernel.memoryLock.acquire();

		// Keep track of vpns for each section
		int pages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.isReadOnly())
				code += section.getLength();
			else
				data += section.getLength();
			pages += coff.getSection(s).getLength();
		}
		coffPages = new CoffPage[pages];
		map = new SectionMap(numPages, code, data);

		// Load coff entries
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			// Load section number
			for (int i = 0; i < section.getLength(); i++) {
				Machine.stats.numCOFFReads++;
				int vpn = section.getFirstVPN() + i;
				coffPages[vpn] = new CoffPage(s, i);
			}
		}

		// Initialize swap entries
		spns = new int[numPages];
		for (int i = 0; i < numPages; i++)
			spns[i] = -1;

		VMKernel.memoryLock.release();

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
		for (int i = 0; i < TLBsize; i++) {
			TranslationEntry entry = processor.readTLBEntry(i);
			if (entry.dirty || entry.used) { // TODO: multi
				pageTable[entry.vpn].dirty = entry.dirty;
				pageTable[entry.vpn].used = entry.used;
			}
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
