// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Hai phép tính nhỏ quyết định truy vấn HNSW lọc theo sự kiện có trả đủ k dòng hay không (R-29). */
class PgVectorStoreAdapterTest {

    @Test
    void ef_search_co_san_40_va_tran_1000() {
        assertThat(PgVectorStoreAdapter.efSearchFor(1, 10)).isEqualTo(40);
        assertThat(PgVectorStoreAdapter.efSearchFor(5, 10)).isEqualTo(50);
        assertThat(PgVectorStoreAdapter.efSearchFor(200, 10)).isEqualTo(1000);
        assertThat(PgVectorStoreAdapter.efSearchFor(Integer.MAX_VALUE, 10)).isEqualTo(1000);
    }

    @Test
    void iterative_scan_tu_pgvector_0_8() {
        assertThat(PgVectorStoreAdapter.atLeast("0.8.6", 0, 8)).isTrue();
        assertThat(PgVectorStoreAdapter.atLeast("0.8", 0, 8)).isTrue();
        assertThat(PgVectorStoreAdapter.atLeast("1.0.0", 0, 8)).isTrue();
        assertThat(PgVectorStoreAdapter.atLeast("0.7.4", 0, 8)).isFalse();
        assertThat(PgVectorStoreAdapter.atLeast("?", 0, 8)).isFalse();
    }
}
