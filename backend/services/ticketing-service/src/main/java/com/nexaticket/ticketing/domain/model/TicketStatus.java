// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

/** Vòng đời một vé. */
public enum TicketStatus {
    VALID,
    CHECKED_IN,

    /** Hoàn tiền, huỷ sự kiện, hoặc phát hiện gian lận. Không quay lại VALID được. */
    REVOKED
}
