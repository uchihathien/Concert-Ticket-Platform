// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import java.util.UUID;

/** Nhật ký mọi lần quét, kể cả những lần bị từ chối. */
public interface CheckinLogRepository {

    void record(UUID ticketId, UUID eventSessionId, UUID staffId, String deviceId, CheckinResult result, String note);
}
