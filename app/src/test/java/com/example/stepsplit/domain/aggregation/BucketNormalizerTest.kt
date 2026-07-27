package com.example.stepsplit.domain.aggregation

import org.junit.Assert.assertEquals
import org.junit.Test

class BucketNormalizerTest {

    @Test
    fun `a single minute-aligned interval becomes one bucket`() {
        val result = BucketNormalizer.normalize(listOf(RawInterval(60, 120, 42)))
        assertEquals(listOf(com.example.stepsplit.domain.classification.MinuteBucket(60, 42)), result)
    }

    @Test
    fun `a multi-minute interval is distributed evenly across the minutes it spans`() {
        val result = BucketNormalizer.normalize(listOf(RawInterval(0, 180, 30)))

        assertEquals(3, result.size)
        assertEquals(30L, result.sumOf { it.steps })
        result.forEach { assertEquals(10L, it.steps) }
    }

    @Test
    fun `remainder steps go to the earliest minutes so nothing is lost`() {
        val result = BucketNormalizer.normalize(listOf(RawInterval(0, 180, 10)))

        assertEquals(10L, result.sumOf { it.steps })
        assertEquals(4L, result[0].steps) // 10 / 3 = 3 remainder 1 -> first minute gets the extra step
    }

    @Test
    fun `overlapping intervals within one read are summed, not duplicated per source point`() {
        val result = BucketNormalizer.normalize(
            listOf(RawInterval(0, 60, 20), RawInterval(0, 60, 15)),
        )

        assertEquals(1, result.size)
        assertEquals(35L, result.single().steps)
    }

    @Test
    fun `zero-step intervals are dropped`() {
        val result = BucketNormalizer.normalize(listOf(RawInterval(0, 60, 0)))
        assertEquals(emptyList<Any>(), result)
    }
}
