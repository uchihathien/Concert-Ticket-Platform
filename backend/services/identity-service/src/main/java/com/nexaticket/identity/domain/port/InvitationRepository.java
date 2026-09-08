// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Optional;

public interface InvitationRepository {

    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findPending(TenantId organizationId);

    Optional<Invitation> findById(java.util.UUID id);

    void save(Invitation invitation);

    /**
     * Thu hồi lời mời chưa dùng bằng cách xoá hẳn dòng.
     *
     * <p>Không thêm cột {@code revoked_at}: lời mời chưa dùng không có giá trị lịch sử nào, còn vết
     * "ai thu hồi lúc nào" thì đã nằm ở {@code audit_logs} — chỗ đúng của nó. Thêm một trạng thái
     * nữa vào bảng là thêm một nhánh phải nhớ ở mọi câu truy vấn sau này.
     */
    void delete(java.util.UUID id);
}
