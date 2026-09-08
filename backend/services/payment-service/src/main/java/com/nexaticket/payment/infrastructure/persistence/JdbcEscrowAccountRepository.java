// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.persistence;

import com.nexaticket.payment.domain.model.EscrowBankAccount;
import com.nexaticket.payment.domain.port.EscrowAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEscrowAccountRepository implements EscrowAccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcEscrowAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EscrowBankAccount> preferred() {
        return jdbc
                .query(
                        """
                        SELECT id, bank_bin, bank_name, account_number, account_name, is_active, is_preferred
                          FROM escrow_bank_accounts
                         WHERE is_preferred AND is_active
                        """,
                        JdbcEscrowAccountRepository::map)
                .stream()
                .findFirst();
    }

    @Override
    public List<EscrowBankAccount> all() {
        return jdbc.query(
                """
                SELECT id, bank_bin, bank_name, account_number, account_name, is_active, is_preferred
                  FROM escrow_bank_accounts ORDER BY created_at
                """,
                JdbcEscrowAccountRepository::map);
    }

    @Override
    public void save(EscrowBankAccount account) {
        jdbc.update(
                """
                INSERT INTO escrow_bank_accounts (id, bank_bin, bank_name, account_number, account_name,
                                                  is_active, is_preferred)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                account.id(),
                account.bankBin(),
                account.bankName(),
                account.accountNumber(),
                account.accountName(),
                account.active(),
                account.preferred());
    }

    private static EscrowBankAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new EscrowBankAccount(
                rs.getObject("id", UUID.class),
                rs.getString("bank_bin"),
                rs.getString("bank_name"),
                rs.getString("account_number"),
                rs.getString("account_name"),
                rs.getBoolean("is_active"),
                rs.getBoolean("is_preferred"));
    }
}
