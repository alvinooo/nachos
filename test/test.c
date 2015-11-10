#include "syscall.h"

int main(int argc, char** argv)
{
  char buffer[10];
  char input[1];
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
  while (1) {
    read(0, input, 1);
    printf("%s", input);
    if (input[0] == 'q')
      break;
  }
  printf("TESTS PASSED\n");
  halt();
  return 0;
}
