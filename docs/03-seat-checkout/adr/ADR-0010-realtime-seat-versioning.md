# ADR-0010: WebSocket availability versioning

**Status:** Accepted

Inventory phát `seat.availability.changed` với `sessionId`, monotonic `version` và changed seats. Client phát hiện version gap phải refetch seat set, không tự suy diễn state còn thiếu.
