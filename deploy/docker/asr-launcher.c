/* Native exec launcher: the model cannot allocate the entire application's cgroup budget.
 * Limits are conservative failure boundaries, not measured whisper.cpp memory requirements. */
#include <sys/resource.h>
#include <unistd.h>
#include <stdio.h>
int main(int argc, char **argv) {
    (void)argc;
    /* RLIMIT_AS bounds virtual mappings, not resident RAM. The pinned tiny model
     * exceeds 512 MiB of virtual space on Linux arm64 during compute allocation.
     * Cgroup admission and the single-job gate remain the physical-memory controls. */
    const rlim_t max_address_space = 1024ULL * 1024 * 1024;
    struct rlimit memory = {max_address_space, max_address_space};
    struct rlimit cpu = {1200, 1200};
    if (setrlimit(RLIMIT_AS, &memory) || setrlimit(RLIMIT_CPU, &cpu)) return 125;
    argv[0] = "/opt/agenvas/lib/whisper/whisper-cli";
    execv(argv[0], argv);
    return 126;
}
