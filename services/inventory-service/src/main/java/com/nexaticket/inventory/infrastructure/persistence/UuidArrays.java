// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

/**
 * Truyền danh sách UUID xuống PostgreSQL bằng {@code = ANY(?)}.
 *
 * <p>Dùng mảng chứ không nối chuỗi {@code IN (?, ?, ?)}: số tham số thay đổi theo số ghế khách chọn,
 * nên nối chuỗi sẽ sinh một câu SQL khác nhau cho mỗi kích thước và phá sạch plan cache của
 * PostgreSQL — đúng chỗ ta cần nó nhất.
 */
final class UuidArrays {

    private UuidArrays() {}

    static Array of(PreparedStatement ps, Collection<UUID> ids) throws SQLException {
        return ps.getConnection().createArrayOf("uuid", ids.toArray(UUID[]::new));
    }
}
