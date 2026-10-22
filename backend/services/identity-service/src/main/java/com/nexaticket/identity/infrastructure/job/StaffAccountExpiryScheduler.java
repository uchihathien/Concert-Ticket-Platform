// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.job;

import com.nexaticket.identity.application.command.StaffAccountHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Khoá tài khoản nhân viên soát vé đã hết việc, định kỳ.
 *
 * <p>Mười phút là đủ: mốc hết hạn đã cộng sẵn vài giờ sau suất cuối, nên trễ thêm một vòng quét
 * không đổi được gì. Tắt được bằng {@code nexaticket.identity.staff-accounts.expiry.enabled=false}
 * (test tự gọi {@link StaffAccountHandler#expireDue} để không phụ thuộc đồng hồ thật).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "nexaticket.identity.staff-accounts.expiry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StaffAccountExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaffAccountExpiryScheduler.class);

    private final StaffAccountHandler accounts;

    public StaffAccountExpiryScheduler(StaffAccountHandler accounts) {
        this.accounts = accounts;
    }

    @Scheduled(
            fixedDelayString = "${nexaticket.identity.staff-accounts.expiry.interval:PT10M}",
            initialDelayString = "${nexaticket.identity.staff-accounts.expiry.initial-delay:PT1M}")
    public void expireDue() {
        try {
            accounts.expireDue();
        } catch (RuntimeException e) {
            // Một lượt hỏng không được giết bộ lập lịch: lượt sau thử lại.
            log.warn("Lượt khoá tài khoản nhân viên hết hạn bị lỗi: {}", e.getMessage(), e);
        }
    }
}
