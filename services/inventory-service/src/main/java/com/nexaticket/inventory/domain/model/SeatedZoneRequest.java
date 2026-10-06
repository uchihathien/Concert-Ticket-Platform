// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/**
 * Xin một số lượng vé <b>ngồi</b> ở một khu, không chỉ đích danh ghế nào.
 *
 * <h2>Khác gì {@link StandingRequest}</h2>
 *
 * <p>Hình thức giống hệt — khu và số lượng — nhưng hai thứ được cấp phát khác nhau, nên chúng là
 * hai kiểu chứ không phải một kiểu kèm cờ:
 *
 * <ul>
 *   <li>vé đứng lấy đơn vị nào cũng như nhau, nên cấp phát theo {@code id} cho rẻ;
 *   <li>vé ngồi thì <b>có</b> chỗ hơn chỗ kém: hàng gần sân khấu được cấp trước.
 * </ul>
 *
 * <p>Và trần mua mỗi lần giữ chỗ đếm chúng vào hai ô khác nhau ({@code maxSeatedPerHold} với
 * {@code maxStandingPerHold}), nên gộp làm một kiểu sẽ khiến một trong hai trần được thi hành sai.
 *
 * <h2>Khác gì việc chỉ đích danh ghế</h2>
 *
 * <p>Không đi qua cổng Redis. Cổng ấy từ chối nhanh theo <b>mã ghế cụ thể</b> (ADR-1012) — ở đây
 * chưa có mã nào để mà từ chối, vì việc chọn ghế nào chính là việc database sắp làm. Đổi lại,
 * {@code FOR UPDATE SKIP LOCKED} bỏ qua đúng những dòng người khác đang giữ, nên hai người cùng
 * xin "khu A, 2 vé" nhận hai cặp ghế khác nhau mà không ai phải chờ ai.
 */
public record SeatedZoneRequest(String zoneCode, int quantity) {

    public SeatedZoneRequest {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw new IllegalArgumentException("zoneCode không được rỗng");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity phải dương, nhận " + quantity);
        }
    }
}
