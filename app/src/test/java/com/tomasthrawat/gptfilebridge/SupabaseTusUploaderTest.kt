package com.tomasthrawat.gptfilebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseTusUploaderTest {
    @Test
    fun chunkSizeIsSixMiB() {
        assertEquals(6L * 1024L * 1024L, SupabaseTusUploader.CHUNK_SIZE)
    }
}
