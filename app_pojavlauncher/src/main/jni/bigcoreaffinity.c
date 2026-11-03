//
// Refactored for Xuanjie O1 & Snapdragon Flagship SoCs (Multi-Big-Core Cluster)
//

#define _GNU_SOURCE

#include <linux/limits.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sched.h>
#include <string.h>

#define FREQ_MAX 256
#define MAX_CORES 32

static void bigcore_format_cpu_path(char* buffer, unsigned int cpu_core) {
    snprintf(buffer, PATH_MAX, "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", cpu_core);
}

void bigcore_set_affinity() {
    char path_buffer[PATH_MAX];
    char freq_buffer[FREQ_MAX];
    char* discard;
    unsigned long freqs[MAX_CORES] = {0};
    unsigned long max_freq = 0;
    unsigned int corecnt = 0;

    // 1. 扫描所有可用核心的最大频率
    while (corecnt < MAX_CORES) {
        bigcore_format_cpu_path(path_buffer, corecnt);
        int corefreqfd = open(path_buffer, O_RDONLY);
        if (corefreqfd != -1) {
            ssize_t read_count = read(corefreqfd, freq_buffer, FREQ_MAX - 1);
            close(corefreqfd);
            if (read_count > 0) {
                freq_buffer[read_count] = 0;
                unsigned long core_freq = strtoul(freq_buffer, &discard, 10);
                freqs[corecnt] = core_freq;
                if (core_freq > max_freq) {
                    max_freq = core_freq;
                }
            }
        } else {
            break;
        }
        corecnt++;
    }

    if (corecnt == 0 || max_freq == 0) {
        printf("bigcore: unable to determine CPU frequencies\n");
        return;
    }

    // 2. 阈值设定为最高频的 80%，容纳超大核与高性能大核集群
    unsigned long threshold = (unsigned long)(max_freq * 0.80);
    cpu_set_t bigcore_affinity_set;
    CPU_ZERO(&bigcore_affinity_set);
    unsigned int bound_cores = 0;

    for (unsigned int i = 0; i < corecnt; i++) {
        if (freqs[i] >= threshold) {
            CPU_SET_S(i, CPU_SETSIZE, &bigcore_affinity_set);
            bound_cores++;
            printf("bigcore: added CPU %u (freq: %lu kHz) to cluster\n", i, freqs[i]);
        }
    }

    // 3. 应用集群亲和性
    int result = sched_setaffinity(0, CPU_SETSIZE, &bigcore_affinity_set);
    if (result != 0) {
        printf("bigcore: setting affinity failed: %s\n", strerror(result));
    } else {
        printf("bigcore: successfully bound thread to %u big/super performance cores (max: %lu kHz)\n", bound_cores, max_freq);
    }
}
