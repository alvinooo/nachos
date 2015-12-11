#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main()
{
  int i;
  int *ptr = &i;
  int fd = creat("file.txt");
  int PID = exec("open2.coff", 0, 0);
  join(PID, ptr);
  int status = unlink("file.txt");
  if (status != 0)
  {
    printf("y u still here???\n");
    return 0;
  }
  return 0;
}
