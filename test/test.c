#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main(int argc, char** argv)
{
  char buffer[10];
  int bytes;
  int fd = creat("test.txt");
  if (fd == -1) {
    printf("file could not be created\n");
    return 0;
  }
  if ((bytes = write(fd, "123456789", 10) != 10)) {
    printf("write failed\n");
    return 0;
  }
  if (close(fd) == -1) {
    printf("file could not be closed\n");
    return 0;
  }
  if ((fd = open("test.txt")) == -1) {
    printf("file could not be opened\n");
    return 0;
  }
  if ((bytes = read(fd, buffer, 10)) != 10) {
    printf("write failed\n");
    return 0;
  }
  printf("%s\n", buffer);
  if (unlink("test.txt") == -1) {
    printf("why r u here?\n");
    return 0;
  }
  if (unlink("test.txt") != -1) {
    printf("why r u still here?\n");
    return 0;
  }
  printf("Testing exec\n");
  int PID;
  int *ptr;
  PID = exec("write1.coff", 0, 0);
  if (PID == -1)
  {
    printf("Exec failed\n");
    return 0;
  }
  int joinstatus = join(PID, ptr);
  if (joinstatus == 0)
  {
    printf("write1 unhandled exception\n");
    return 0;
  }
  printf("TESTS PASSED\n");
  close(1);
  printf("This shouldn't print\n");
  return 0;
}
