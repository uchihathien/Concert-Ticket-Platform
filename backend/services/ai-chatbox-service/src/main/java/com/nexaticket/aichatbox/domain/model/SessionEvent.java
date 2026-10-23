// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.UUID;

/** Suất diễn thuộc sự kiện nào — đủ để kể lại khách đã mua vé gì. */
public record SessionEvent(UUID eventSessionId, UUID eventId, String eventTitle) {}
