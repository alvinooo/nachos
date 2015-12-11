#include "syscall.h"

int main()
{
  int i;
  int *exit = &i;
  int PID = 0;
  int JOIN = 0;
  while (PID != -1)
  {
    PID = exec("bomb.coff", 0, 0);
    JOIN = join(PID, exit);
    printf("PID %d exit status %d with join status %d\n", PID, *exit, JOIN);
  }
  return 1;
}
