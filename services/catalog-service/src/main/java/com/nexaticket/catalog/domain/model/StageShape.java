// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Hình dạng sân khấu — thứ khách nhìn vào để biết mình đang ngồi ở đâu.
 *
 * <p>Sân khấu <b>không</b> sinh ra chỗ ngồi nào và không tham gia vào tồn kho. Nó tồn tại ở đây vì
 * một sơ đồ không có sân khấu là một đám ô vuông không nói lên hướng nhìn: "hàng A" ở gần hay xa
 * là câu hỏi chỉ trả lời được khi biết sân khấu nằm đâu.
 */
public enum StageShape {

    /** Sân khấu hộp cổ điển, khán giả ngồi một phía. */
    RECTANGLE,

    /** Sân khấu tròn giữa khán phòng, khán giả quây bốn phía. */
    CIRCLE,

    /** Sân khấu nhô ra giữa khán đài — khán giả ngồi ba phía. Hình chữ U của khán phòng. */
    THRUST
}
