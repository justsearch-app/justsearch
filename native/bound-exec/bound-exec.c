/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

static int fail(int status, const char *reason) {
  fprintf(stderr, "justsearch-bound-exec: %s\n", reason);
  return status;
}

static int decimal(const char *text, unsigned long long *value) {
  if (!text || !*text || (text[0] == '0' && text[1])) return 0;
  for (const char *p = text; *p; p++) if (*p < '0' || *p > '9') return 0;
  errno = 0;
  char *end;
  *value = strtoull(text, &end, 10);
  return !errno && !*end;
}

static int uuid(const char *text) {
  if (strlen(text) != 36) return 0;
  for (int i = 0; i < 36; i++) {
    if (i == 8 || i == 13 || i == 18 || i == 23) { if (text[i] != '-') return 0; }
    else if (!((text[i] >= '0' && text[i] <= '9') || (text[i] >= 'a' && text[i] <= 'f'))) return 0;
  }
  return 1;
}

static int signal_number(const char *name) {
  if (!strcmp(name, "0")) return 0;
  if (!strncmp(name, "SIG", 3)) name += 3;
  static const struct { const char *name; int number; } signals[] = {
    {"KILL", SIGKILL}, {"TERM", SIGTERM}, {"INT", SIGINT}, {"HUP", SIGHUP},
    {"QUIT", SIGQUIT}, {"STOP", SIGSTOP}, {"CONT", SIGCONT}, {"USR1", SIGUSR1},
    {"USR2", SIGUSR2}, {"ABRT", SIGABRT}, {"PIPE", SIGPIPE}, {"ALRM", SIGALRM},
    {"ILL", SIGILL}, {"TRAP", SIGTRAP}, {"BUS", SIGBUS}, {"FPE", SIGFPE},
    {"SEGV", SIGSEGV}, {"STKFLT", SIGSTKFLT}, {"CHLD", SIGCHLD}, {"TSTP", SIGTSTP},
    {"TTIN", SIGTTIN}, {"TTOU", SIGTTOU}, {"URG", SIGURG}, {"XCPU", SIGXCPU},
    {"XFSZ", SIGXFSZ}, {"VTALRM", SIGVTALRM}, {"PROF", SIGPROF}, {"WINCH", SIGWINCH},
    {"IO", SIGIO}, {"PWR", SIGPWR}, {"SYS", SIGSYS}
  };
  for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++)
    if (!strcmp(name, signals[i].name)) return signals[i].number;
  return -1;
}

static int read_file(const char *path, char *buffer, size_t size) {
  FILE *file = fopen(path, "re");
  if (!file) return errno == ENOENT || errno == ESRCH ? 3 : 2;
  size_t count = fread(buffer, 1, size - 1, file);
  int error = ferror(file) || !feof(file);
  fclose(file);
  if (error || !count) return 2;
  buffer[count] = 0;
  return 0;
}

static int signal_process(int argc, char **argv) {
  unsigned long long pid, expected_ticks;
  int number = argc == 6 ? signal_number(argv[5]) : -1;
  if (argc != 6 || !decimal(argv[2], &pid) || pid == 0 || pid > INT_MAX || !uuid(argv[3])
      || !decimal(argv[4], &expected_ticks) || number < 0)
    return fail(2, "invalid signal arguments");
  /* Acquire authority before looking at /proc, never signal a newly looked-up PID. */
  int fd = (int) syscall(SYS_pidfd_open, (pid_t) pid, 0);
  if (fd < 0) return fail(errno == ESRCH ? 3 : 2, "pidfd_open failed");
  char path[64], stat[8192], boot[64];
  snprintf(path, sizeof(path), "/proc/%llu/stat", pid);
  int result = read_file(path, stat, sizeof(stat));
  if (result) { close(fd); return fail(result, "cannot read process identity"); }
  /* comm may contain spaces, newlines and ')'; field 3 follows its final ')'. */
  char *last = strrchr(stat, ')');
  if (!last || last[1] != ' ' || !last[2]) { close(fd); return fail(2, "invalid process stat"); }
  if (last[2] == 'Z' || last[2] == 'X' || last[2] == 'x') {
    close(fd); return fail(3, "process ended");
  }
  char *save = NULL, *token = strtok_r(last + 2, " \n", &save);
  for (int field = 3; field < 22 && token; field++) token = strtok_r(NULL, " \n", &save);
  unsigned long long actual_ticks;
  if (!token || !decimal(token, &actual_ticks)) { close(fd); return fail(2, "invalid start ticks"); }
  result = read_file("/proc/sys/kernel/random/boot_id", boot, sizeof(boot));
  if (result) { close(fd); return fail(2, "cannot read boot identity"); }
  boot[strcspn(boot, "\r\n")] = 0;
  if (!uuid(boot)) { close(fd); return fail(2, "invalid boot identity"); }
  if (actual_ticks != expected_ticks || strcmp(boot, argv[3])) {
    close(fd); return fail(3, "process identity mismatch");
  }
  result = (int) syscall(SYS_pidfd_send_signal, fd, number, NULL, 0);
  int error = errno;
  close(fd);
  if (result) return fail(error == ESRCH ? 3 : 2, "pidfd_send_signal failed");
  return 0;
}

int main(int argc, char **argv) {
  if (argc > 1 && !strcmp(argv[1], "signal")) return signal_process(argc, argv);
  unsigned long long parent;
  if (argc < 5 || strcmp(argv[1], "exec") || !decimal(argv[2], &parent)
      || parent == 0 || parent > INT_MAX || strcmp(argv[3], "--") || !*argv[4])
    return fail(125, "invalid exec arguments");
  if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0L, 0L, 0L)) return fail(125, "prctl failed");
  if (getppid() != (pid_t) parent) return fail(125, "parent identity mismatch");
  execvp(argv[4], argv + 4);
  int error = errno;
  fprintf(stderr, "justsearch-bound-exec: execvp failed (errno=%d): %s\n", error, strerror(error));
  return 125;
}
