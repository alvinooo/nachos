#include "syscall.h"
#include "stdio.h"
#include "stdlib.h"

int main()
{
  int i, *ptr;
  ptr = &i;
  while (1)
  {
    int PID = exec("ping.coff", 0, 0);
    int ret = join(PID, ptr);
    printf("%s\n", ret == 1? "pong": "nope\n");
  }
  return 0;
}
