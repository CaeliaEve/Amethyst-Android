#!/usr/bin/env python3
"""
Unit tests for CPU Big Core Cluster Affinity Mask calculation.
Simulates calculate_bigcore_mask algorithm from bigcoreaffinity.c
"""

import sys

def calculate_bigcore_mask(freqs):
    """
    Python implementation mirroring calculate_bigcore_mask in bigcoreaffinity.c
    Returns: (big_core_count, mask_bitfield)
    """
    core_count = len(freqs)
    if core_count <= 0:
        return -1, 0
    max_freq = max(freqs)
    if max_freq == 0:
        return -1, 0

    # 选取频率高于最高频 80% 的所有核心（包含超大核与大核）
    threshold = int(max_freq * 0.80)
    mask = 0
    big_core_count = 0
    for i in range(core_count):
        if freqs[i] >= threshold:
            mask |= (1 << i)
            big_core_count += 1
    return big_core_count, mask


def test_soc_8gen2_and_xuanjie():
    # 模拟玄戒 O1 / 8 Gen 2 架构: 1 超大核(3.2GHz) + 4 大核(2.8GHz) + 3 小核(2.0GHz)
    test_soc = [
        2000000, 2000000, 2000000,         # cpu0-2 小核
        2800000, 2800000, 2800000, 2800000, # cpu3-6 大核
        3200000                             # cpu7 超大核
    ]
    count, mask = calculate_bigcore_mask(test_soc)
    assert count == 5, f"Expected 5 big cores, got {count}"
    assert (mask & (1 << 0)) == 0, "cpu0 should not be set"
    assert (mask & (1 << 1)) == 0, "cpu1 should not be set"
    assert (mask & (1 << 2)) == 0, "cpu2 should not be set"
    assert (mask & (1 << 3)) != 0, "cpu3 should be set"
    assert (mask & (1 << 4)) != 0, "cpu4 should be set"
    assert (mask & (1 << 5)) != 0, "cpu5 should be set"
    assert (mask & (1 << 6)) != 0, "cpu6 should be set"
    assert (mask & (1 << 7)) != 0, "cpu7 should be set"
    print(f"PASS: bigcore cluster mask test successfully identified {count} performance cores")


def test_soc_homogeneous():
    # 均质核心 (8核等频)
    soc = [2500000] * 8
    count, mask = calculate_bigcore_mask(soc)
    assert count == 8
    assert mask == 0xFF
    print("PASS: homogeneous cores identified 8 cores")


def test_edge_cases():
    # 空核心 / 0频率
    count, mask = calculate_bigcore_mask([])
    assert count == -1
    count, mask = calculate_bigcore_mask([0, 0, 0])
    assert count == -1
    print("PASS: edge cases handled gracefully")


if __name__ == "__main__":
    print("Running bigcoreaffinity unit tests...")
    test_soc_8gen2_and_xuanjie()
    test_soc_homogeneous()
    test_edge_cases()
    print("ALL TESTS PASSED")
