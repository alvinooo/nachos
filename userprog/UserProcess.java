package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.VMKernel;
import nachos.vm.VMProcess;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {

		if (processLock == null)
			processLock = new Lock();

		if (freePages == null) {
			int numPhysPages = Machine.processor().getNumPhysPages();

			// Initialize free physical memory
			freePages = new LinkedList<PageNode>();
			for (int i = 0; i < numPhysPages; i++) {
				freePages.add(new PageNode(i));
			}
			if (memlock == null)
				memlock = new Lock();
		}
		exit = -1;
		pages = new LinkedList<PageNode>();
		PID = CurrentPID++;
		children = new HashMap<Integer, UserProcess>();

		int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		for (int i = 0; i < numPhysPages; i++)
			pageTable[i] = new TranslationEntry(i, i, true, false, false, false);

		files[0] = UserKernel.console.openForReading();
		files[1] = UserKernel.console.openForWriting();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);
		thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr
	 *            the starting virtual address of the null-terminated string.
	 * @param maxLength
	 *            the maximum number of characters in the string, not including
	 *            the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 *         found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to read.
	 * @param data
	 *            the array where the data will be stored.
	 * @param offset
	 *            the first byte to write in the array.
	 * @param length
	 *            the number of bytes to transfer from virtual memory to the
	 *            array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// Read page by page in case buffer is split between pages
		int bytes = 0;
		while (length > 0 && offset < data.length) {

			// Check virtual address
			if (vaddr < 0 || vaddr >= numPages * pageSize - 1)
				return 0;

			// Calculate page number and offset
			int vpn = Processor.pageFromAddress(vaddr);

			// Handle invalid pages
			if (!pageTable[vpn].valid)
				handlePageFault(vpn);

			int ppn = pageTable[vpn].ppn;
			int pageOffset = Processor.offsetFromAddress(vaddr);
			int paddr = ppn * pageSize + pageOffset;

			// Copy as much data as the page size allows
			int chunk = Math.min(length, pageSize - pageOffset);
			System.arraycopy(memory, paddr, data, offset, chunk);

			// Update the pointers in memory and buffer
			vaddr += chunk;
			offset += chunk;

			// Update the transferred and remaining bytes
			bytes += chunk;
			length -= chunk;
		}
		return bytes;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr
	 *            the first byte of virtual memory to write.
	 * @param data
	 *            the array containing the data to transfer.
	 * @param offset
	 *            the first byte to transfer from the array.
	 * @param length
	 *            the number of bytes to transfer from the array to virtual
	 *            memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// Check virtual address
		if (vaddr < 0 || vaddr >= numPages * pageSize - 1)
			return 0;

		// Write page by page in case of non-contiguous pages
		int bytes = 0;
		while (length > 0 && offset < data.length) {

			// Calculate page number and offset
			int vpn = Processor.pageFromAddress(vaddr);

			// Handle invalid pages
			if (!pageTable[vpn].valid)
				handlePageFault(vpn);

			int ppn = pageTable[vpn].ppn;
			int pageOffset = Processor.offsetFromAddress(vaddr);
			int paddr = ppn * pageSize + pageOffset;

			// Check read-only
			if (pageTable[vpn].readOnly)
				return -1;

			// Copy as much data as the page size allows
			int chunk = Math.min(length, pageSize - pageOffset);
			System.arraycopy(data, offset, memory, paddr, chunk);

			// Update the pointers in memory and buffer
			vaddr += chunk;
			offset += chunk;

			// Update the transferred and remaining bytes
			bytes += chunk;
			length -= chunk;
		}

		return bytes;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name
	 *            the name of the file containing the executable.
	 * @param args
	 *            the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		} catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// Initialize page table
		pageTable = new TranslationEntry[numPages];

		// Synchronize access to free pages and main memory
		memlock.acquire();
		for (int i = 0; i < numPages; i++) {

			int ppn;

			// Free pages if main memory runs out of pages for process
			if (freePages.isEmpty()) {
				memlock.release();
				unloadSections();
				coff.close();
				return false;
			}

			// Load a free page into page table
			PageNode page = freePages.removeFirst();
			page.PID = PID;
			pages.add(page);
			ppn = page.index;

			// Load into page table and main memory
			pageTable[i] = new TranslationEntry(i, ppn, true, false, false,
					false);
		}
		memlock.release();

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			// Load section into physical address
			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				int ppn = pageTable[vpn].ppn;
				section.loadPage(i, ppn);
				if (section.isReadOnly())
					pageTable[vpn].readOnly = true;
			}
		}

		processLock.acquire();
		numProcesses++;
		processLock.release();

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		memlock.acquire();
		int allocated = pages.size();
		for (int j = 0; j < allocated; j++)
			freePages.add(pages.removeFirst());
		memlock.release();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {

		if (PID != 0)
			return 0;

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	/**
	 * Handle the exit() system call. And helper finish function
	 */
	private void handleExit(int status) {
		System.out.println("status = " + status); // TODO: remove
		if (parent != null)
			parent.children.get(PID).exit = status;
		finish();
	}

	private void finish() {

		// Clean up
		for (int i = 0; i < MAX_FILES; i++)
			handleClose(i);
		unloadSections();
		coff.close();

		processLock.acquire();
		numProcesses--;
		processLock.release();

		// Last process
		if (numProcesses == 0)
			UserKernel.kernel.terminate();

		// Wake up existing parent thread
		UThread.finish();
	}

	/**
	 * Handle the exec() system call.
	 */
	private int handleExec(int filename, int argc, int argv) {
		if (filename <= 0 || filename > numPages * pageSize - 1 || argc < 0
				|| argv < 0)
			return -1;

		// Read the process name
		String file = readVirtualMemoryString(filename, 255);

		// Read in the arguments
		String[] split = new String[argc];
		byte ptr[] = new byte[4];
		for (int i = 0; i < argc; i++) {
			readVirtualMemory(argv + i * 4, ptr);
			split[i] = readVirtualMemoryString(Lib.bytesToInt(ptr, 0), 255);
			if (split[i] == null)
				return -1;
		}

		// Create and execute child process
		UserProcess child = new UserProcess();
		child.parent = this;
		children.put(child.PID, child);
		if (child.execute(file, split)) {
			return child.PID;
		}

		return -1;
	}

	/**
	 * Handle the join() system call.
	 */
	private int handleJoin(int processID, int statusPtr) {

		// Validate parameters
		if (processID < 0 || statusPtr < 0)
			return -1;

		// Join the child
		UserProcess child = children.get(processID);

		if (child == null)
			return -1;

		child.thread.join();

		// Write the exit code to the parent process
		children.remove(processID);
		writeVirtualMemory(statusPtr, Lib.bytesFromInt(child.exit));

		// Check exit status
		return child.exit != -1 ? 1 : 0;
	}

	/**
	 * Handle the creat() system call.
	 */
	private int handleCreate(int name) {
		// Validate parameters
		if (name <= 0 || name > numPages * pageSize - 1)
			return -1;

		// Search for available file descriptor
		String fileName = readVirtualMemoryString(name, 255);
		for (int fd = 0; fd < MAX_FILES; fd++) {
			if (files[fd] == null) {
				OpenFile file = ThreadedKernel.fileSystem.open(fileName, true);
				files[fd] = file;
				return fd;
			} else if (files[fd].getName().equals(fileName)) {
				return fd;
			}
		}

		// Max files open
		return -1;
	}

	/**
	 * Handle the open() system call.
	 */
	private int handleOpen(int name) {
		// Validate parameters
		if (name <= 0 || name > numPages * pageSize - 1)
			return -1;

		// Check if file exists
		String fileName = readVirtualMemoryString(name, 256);
		OpenFile file = ThreadedKernel.fileSystem.open(fileName, false);
		if (file == null)
			return -1;

		// Search for available file descriptor
		for (int fd = 0; fd < MAX_FILES; fd++) {
			if (files[fd] == null) {
				files[fd] = file;
				return fd;
			}
		}

		// Max files open
		return -1;
	}

	/**
	 * Handle the read() system call.
	 */
	private int handleRead(int fd, int ptr, int amount) {
		byte buffer[] = new byte[pageSize];

		// Validate parameters
		if (fd < 0 || fd > MAX_FILES - 1 || files[fd] == null || ptr < 0
				|| amount < 0 || ptr > numPages * pageSize - 1
				|| ptr + amount >= numPages * pageSize)
			return -1;

		int transferred = 0;
		while (amount > 0) {

			int size = Math.min(pageSize, amount);

			// Read from file or STDIN
			int read = files[fd].read(buffer, 0, size);
			if (read == -1)
				return -1;

			// Write buffer to virtual memory
			int wrote = writeVirtualMemory(ptr, buffer, 0, read);
			if (wrote == -1 || wrote < read)
				return -1;
			transferred += read;
			ptr += read;
			amount -= read;
			if (read < size)
				break;
		}
		return transferred;
	}

	/**
	 * Handle the write() system call.
	 */
	private int handleWrite(int fd, int ptr, int amount) {
		byte buffer[] = new byte[pageSize];

		// Validate parameters
		if (fd < 0 || fd > MAX_FILES - 1 || files[fd] == null || ptr < 0
				|| amount < 0 || ptr > numPages * pageSize - 1
				|| ptr + amount >= numPages * pageSize)
			return -1;

		// Read buffer from virtual memory
		int transferred = 0;
		while (amount > 0) {

			int size = Math.min(pageSize, amount);

			// Read buffer from virtual memory
			int read = readVirtualMemory(ptr, buffer, 0, size);
			if (read == -1)
				return -1;

			// Write to STDOUT or disk
			int wrote = files[fd].write(buffer, 0, read);
			if (wrote == -1 || wrote < read)
				return -1;
			transferred += read;
			ptr += read;
			amount -= read;
			if (read < size)
				break;
		}

		return transferred;
	}

	/**
	 * Handle the close() system call.
	 */
	private int handleClose(int fd) {
		// Validate parameters
		if (fd < 0 || fd > MAX_FILES - 1 || files[fd] == null)
			return -1;

		// Close and free the fd
		files[fd].close();
		files[fd] = null;
		return 0;
	}

	/**
	 * Handle the unlink() system call.
	 */
	private int handleUnlink(int name) {
		// Validate parameters
		if (name <= 0 || name > numPages * pageSize - 1)
			return -1;

		// Check if file exists
		String fileName = readVirtualMemoryString(name, 256);
		OpenFile file = ThreadedKernel.fileSystem.open(fileName, false);
		if (file == null)
			return -1;

		// Check if file is open
		for (int fd = 0; fd < MAX_FILES; fd++) {
			if (files[fd] != null && files[fd].getName().equals(fileName)) {
				files[fd].close();
				files[fd] = null;
				break;
			}
		}

		// Delete file if in file system
		if (!ThreadedKernel.fileSystem.remove(fileName))
			return -1;
		return 0;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall
	 *            the syscall number.
	 * @param a0
	 *            the first syscall argument.
	 * @param a1
	 *            the second syscall argument.
	 * @param a2
	 *            the third syscall argument.
	 * @param a3
	 *            the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
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
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			finish();
			Lib.assertNotReached("Unexpected exception");
		}
	}

	protected TranslationEntry handlePageFault(int vpn) {

		TranslationEntry entry;
		Machine.stats.numPageFaults++;
		VMKernel.Swapper swapper = VMKernel.swapper;
		VMKernel.IPT ipt = swapper.getIPT();
		int ppn = ipt.getPPN();
		int out = -1;

		// Allocate page
		/*
		if (swapper.hasSwapPage(pageTable[vpn])) {
			VMKernel.PageFrame pageFrame = ipt.getPage(ppn);
			if (pageFrame != null)
				out = ipt.getVPN(ppn);
			//entry = allocateSwapPage(vpn, -1, ppn, swapper);
			entry = pageTable[vpn];

			// Read page from swap file
			swapper.readSwap(entry);

			entry.valid = true;
			entry.ppn = ppn;
		} else {
			entry = allocatePhysPage(vpn, ppn);
		}
		*/
		entry = allocatePhysPage(vpn, ppn);

		// Invalidate evicted PTE if necessary
		if (out >= 0) {
			pageTable[out].valid = false;

			// Sync TLB
			Processor processor = Machine.processor();
			for (int i = 0; i < processor.getTLBSize(); i++) {
				TranslationEntry TLBEntry = processor.readTLBEntry(i);
				if (TLBEntry.ppn == ppn && TLBEntry.valid) {
					TLBEntry.valid = false;
					processor.writeTLBEntry(i, TLBEntry);
					break;
				}
			}
		}

		// Sync PTE/IPT
		pageTable[vpn] = new TranslationEntry(entry);
		ipt.update(pageTable[vpn].ppn, (VMProcess) this, new TranslationEntry(
				pageTable[vpn]));
		for (int i = 0; i < numPages; i++)
			System.out.println("PTE vpn: " + pageTable[i].vpn + " ppn: "
					+ pageTable[i].ppn + " valid: " + pageTable[i].valid
					+ " readOnly: " + pageTable[i].readOnly + " dirty: "
					+ pageTable[i].dirty);
		return entry;
	}

	protected TranslationEntry allocateSwapPage(int vpnIn, int vpnOut, int ppn,
			VMKernel.Swapper swapper) {

		// Check if there is a page to evict
		if (vpnOut >= 0) {
			TranslationEntry evict = swapper.getIPT().getProcess(ppn).pageTable[vpnOut];
			evict.valid = false;
			evict.ppn = -1;

			// Write dirty page to swap file
			if (evict.dirty) {
				swapper.writeSwap(evict);
			}
		}

		TranslationEntry replace = pageTable[vpnIn];

		// Read page from swap file
		swapper.readSwap(replace);

		replace.valid = true;
		replace.ppn = ppn;

		return replace;
	}

	protected TranslationEntry allocatePhysPage(int vpn, int ppn) {

		// Check if page is coff or stack
		if (vpn < numPages - stackPages/*pageTable[vpn].readOnly*/) {
			return allocateCodePage(vpn, ppn);
		} else {
			return allocateStackPage(vpn, ppn);
		}
	}

	protected TranslationEntry allocateCodePage(int vpn, int ppn) {
		Machine.stats.numCOFFReads++;
		CoffSection section = coff.getSection(coffPages[vpn].section);
		section.loadPage(coffPages[vpn].spn, ppn);
		return new TranslationEntry(vpn, ppn, true, section.isReadOnly(), false, false);
	}

	protected TranslationEntry allocateStackPage(int vpn, int ppn) {
		byte[] memory = Machine.processor().getMemory();
		int address = Processor.makeAddress(ppn, 0);
		for (int i = address; i < address + Processor.pageSize; i++)
			memory[i] = 0;
		return new TranslationEntry(vpn, ppn, true, false, false, false);
	}

	/** Keeps track of coff section numbers and vpns */
	public class CoffPage {
		public CoffPage(int section, int spn) {
			this.section = section;
			this.spn = spn;
		}

		public int section;
		public int spn;
	}

	protected CoffPage[] coffPages;

	/** Process count and lock */
	protected static int numProcesses;

	protected static Lock processLock;

	/** The program being run by this process. */
	protected Coff coff;

	/** PID and PID counter */
	public int PID;

	protected static int CurrentPID;

	/** Process thread */
	protected UThread thread;

	/** Exit status */
	protected int exit;

	/** Child processes data structure */
	protected HashMap<Integer, UserProcess> children;

	/** Reference to parent pointer to wake after join */
	protected UserProcess parent;

	/** The node for the free page data structure */
	public class PageNode {
		PageNode(int index) {
			this.PID = 0;
			this.index = index;
		}

		public int PID;
		public int index;
	}

	/** Allocated pages */
	protected LinkedList<PageNode> pages;

	/** The free page data structure and lock */
	public static LinkedList<PageNode> freePages;

	protected static Lock memlock;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int MAX_FILES = 16;

	private OpenFile[] files = new OpenFile[MAX_FILES];

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';
}