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

int bigbufnum = 4 * 1024 / sizeof (int);
int bigbuf[4 * 1024 / sizeof (int)];

void
write_buf (char * filename)
{
    int i;
    unlink(filename);
    int fd = creat(filename);

    for (i = 0; i < bigbufnum; i++) {
    bigbuf[i] = i;
    }
    write(fd, bigbuf, bigbufnum * sizeof (int));
}

void
validate_buf ()
{
    int i;
    int fd = open("test0.txt");

    read(fd, bigbuf, bigbufnum * sizeof (int));

    for (i = 0; i < bigbufnum; i++) {
	if (bigbuf[i] != i) {
	    // encode both the index and the bad data value in the status...
	    int s = i * 1000 * 1000;
	    s += bigbuf[i];
        printf("failed on bigbuf[%d] = %d\n", i, bigbuf[i]);
	    exit (s);
	}
    }
}


int
main (int argc, char *argv[])
{
    write_buf ("test0.txt");
    write_buf ("test1.txt");
    write_buf ("test2.txt");
    printf("work?\n");
    validate_buf ();
    printf("worked\n");
    printf("Passed\n");
    exit (-1000);
}
