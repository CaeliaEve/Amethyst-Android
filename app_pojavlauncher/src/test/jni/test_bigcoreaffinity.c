#include <stdio.h>
#include <assert.h>
#include <sched.h>
#include <stdlib.h>

// 模拟待测函数计算掩码逻辑
int calculate_bigcore_mask(const unsigned long* freqs, int core_count, cpu_set_t* mask) {
    if (core_count <= 0) return -1;
    unsigned long max_freq = 0;
    for (int i = 0; i < core_count; i++) {
        if (freqs[i] > max_freq) max_freq = freqs[i];
    }
    if (max_freq == 0) return -1;

    // 选取频率高于最高频 80% 的所有核心（包含超大核与大核）
    unsigned long threshold = (unsigned long)(max_freq * 0.80);
    CPU_ZERO(mask);
    int big_core_count = 0;
    for (int i = 0; i < core_count; i++) {
        if (freqs[i] >= threshold) {
            CPU_SET(i, mask);
            big_core_count++;
        }
    }
    return big_core_count;
}

int main() {
    // 模拟玄戒 O1 / 8 Gen 2 架构: 1 超大核(3.2GHz) + 4 大核(2.8GHz) + 3 小核(2.0GHz)
    unsigned long test_soc[8] = {
        2000000, 2000000, 2000000, // cpu0-2 小核
        2800000, 2800000, 2800000, 2800000, // cpu3-6 大核
        3200000  // cpu7 超大核
    };
    cpu_set_t mask;
    int count = calculate_bigcore_mask(test_soc, 8, &mask);
    
    // 验证必须识别出 5 个高性能核心 (cpu3-7)，不能退化为单核
    assert(count == 5);
    assert(!CPU_ISSET(0, &mask));
    assert(!CPU_ISSET(1, &mask));
    assert(!CPU_ISSET(2, &mask));
    assert(CPU_ISSET(3, &mask));
    assert(CPU_ISSET(6, &mask));
    assert(CPU_ISSET(7, &mask));

    printf("PASS: bigcore cluster mask test successfully identified %d performance cores\n", count);
    return 0;
}
