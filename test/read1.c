/*
 * swap5.c
 *
 * Test swapping by initializing an array larger than physical memory,
 * reading through it, and modifying it.  In each pass, the pages
 * containing array values will get dirty.
 *
 * Note that the program does not use printf to avoid the use of the
 * write system call.  Instead, it uses the exit status to indicate an
 * error or success.  If the data validates, then the program exits
 * with status -1000.  If the data does not validate, then the program
 * exits with a status indicating the index and bad value encountered.
 */

int bigbufnum = 2 * 1024 / sizeof (int);
int bigbuf[2 * 1024 / sizeof (int)];

void
validate_buf (int file)
{
    int i;
    int fd = open("test1.txt");

    read(fd, bigbuf, bigbufnum * sizeof (int));

    for (i = 0; i < bigbufnum; i++) {
	if (bigbuf[i] != i) {
	    // encode both the index and the bad data value in the status...
	    int s = i * 1000 * 1000;
	    s += bigbuf[i];
        printf("failed on bigbuf[%d] = %d\n in process 1", i, bigbuf[i]);
	    exit (s);
	}
    }
}


int
main (int argc, char *argv[])
{
    validate_buf (argc);
    printf("1 Passed\n");
    exit (-1000);
}
