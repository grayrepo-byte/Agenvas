/* Native exec launcher: the model cannot allocate the entire application's cgroup budget.
 * Limits are conservative failure boundaries, not measured whisper.cpp memory requirements. */
#include <sys/resource.h>
#include <unistd.h>
#include <stdio.h>
int main(int argc, char **argv) {
    (void)argc;
    struct rlimit memory = {512ULL * 1024 * 1024, 512ULL * 1024 * 1024};
    struct rlimit cpu = {1200, 1200};
    if (setrlimit(RLIMIT_AS, &memory) || setrlimit(RLIMIT_CPU, &cpu)) return 125;
    argv[0] = "/opt/agenvas/lib/whisper/whisper-cli";
    execv(argv[0], argv);
    return 126;
}
