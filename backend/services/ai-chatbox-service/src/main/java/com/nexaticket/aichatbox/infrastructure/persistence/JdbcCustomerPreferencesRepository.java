// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.CustomerPreferences;
import com.nexaticket.aichatbox.domain.port.CustomerPreferencesRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerPreferencesRepository implements CustomerPreferencesRepository {

    private final JdbcClient db;

    public JdbcCustomerPreferencesRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<CustomerPreferences> find(UUID userId) {
        return db.sql(
                        """
                        select user_id, categories, cities, max_budget_vnd, note, updated_at
                        from customer_preferences where user_id = :userId
                        """)
                .param("userId", userId)
                .query(JdbcCustomerPreferencesRepository::map)
                .optional();
    }

    @Override
    public void save(CustomerPreferences p) {
        db.sql(
                        """
                        insert into customer_preferences
                            (user_id, categories, cities, max_budget_vnd, note, updated_at)
                        values (:userId, :categories, :cities, :maxBudgetVnd, :note, :updatedAt)
                        on conflict (user_id) do update set
                            categories = excluded.categories,
                            cities = excluded.cities,
                            max_budget_vnd = excluded.max_budget_vnd,
                            note = excluded.note,
                            updated_at = excluded.updated_at
                        """)
                .param("userId", p.userId())
                .param("categories", p.categories().toArray(String[]::new))
                .param("cities", p.cities().toArray(String[]::new))
                .param("maxBudgetVnd", p.maxBudgetVnd())
                .param("note", p.note())
                .param("updatedAt", Timestamp.from(p.updatedAt()))
                .update();
    }

    @Override
    public boolean delete(UUID userId) {
        return db.sql("delete from customer_preferences where user_id = :userId")
                        .param("userId", userId)
                        .update()
                > 0;
    }

    private static CustomerPreferences map(ResultSet rs, int row) throws SQLException {
        return new CustomerPreferences(
                rs.getObject("user_id", UUID.class),
                strings(rs.getArray("categories")),
                strings(rs.getArray("cities")),
                rs.getObject("max_budget_vnd", Long.class),
                rs.getString("note"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
