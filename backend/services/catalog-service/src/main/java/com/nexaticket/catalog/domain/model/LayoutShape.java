// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Cách các hàng ghế của một khu nằm trên mặt bằng.
 *
 * <p>Ba hình, và danh sách này đóng lại có chủ đích. Mọi khán phòng thật đều dựng được từ chúng:
 * sân khấu chữ nhật là các khu {@link #GRID} xếp thẳng, sân khấu tròn là các khu {@link #ARC} quây
 * quanh một tâm, sân khấu chữ U là ba khu — một {@code ARC} ôm đầu sân khấu và hai {@code GRID}
 * xoay 90° chạy dọc hai cánh — còn khu bàn tiệc là {@link #TABLE}.
 *
 * <h3>Điều kiện để một hình được nhận vào danh sách này</h3>
 *
 * <p>Nó phải giữ được nghĩa của mã chỗ {@code A-3-12}: "khu A, đơn vị thứ 3, chỗ thứ 12". Cả soát
 * vé lẫn hỗ trợ khách hàng đọc mã này hàng ngày và họ đọc nó thành lời. Với {@code GRID} và
 * {@code ARC} thì "đơn vị" là hàng; với {@code TABLE} là bàn — "bàn 3, ghế 12" vẫn là một câu nói
 * được qua điện thoại.
 *
 * <p>Đa giác tự do hay đường cong Bézier thì không qua được cửa này: màn hình khai báo khu phải
 * biến thành một trình vẽ vector, và mã chỗ mất hẳn nghĩa.
 */
public enum LayoutShape {

    /** Khối chữ nhật {@code rowCount × seatsPerRow}, xoay quanh gốc của khu. */
    GRID,

    /**
     * Các hàng là cung tròn đồng tâm, ghế rải đều theo góc.
     *
     * <p>Đánh đổi có chủ đích: giãn cách giữa hai ghế cạnh nhau <b>tăng dần theo bán kính</b>, vì
     * mọi hàng đều có đúng {@code seatsPerRow} ghế. Khán phòng thật thì hàng ngoài nhiều ghế hơn
     * hàng trong. Giữ số ghế mỗi hàng cố định là thứ cho phép mã chỗ vẫn là {@code zone-row-seat}
     * và Inventory vẫn nhận một danh sách phẳng — đổi lại, hàng ngoài cùng của một khu cung rộng
     * trông thưa hơn thực tế. Khu quá sâu thì tách làm hai khu cung với bán kính khác nhau.
     */
    ARC,

    /**
     * Bàn tròn: {@code rowCount} bàn, mỗi bàn {@code seatsPerRow} ghế quây quanh.
     *
     * <p>Đây là cách ngồi của gala, tiệc cuối năm và đêm nhạc phòng trà — khán giả ngồi quanh bàn
     * đối diện nhau chứ không cùng nhìn về một hướng. Dựng nó bằng {@code GRID} thì mỗi bàn phải
     * là một khu riêng, và một phòng 40 bàn thành 40 khu: màn hình chọn chỗ mất hết ý nghĩa gom
     * nhóm, còn bảng giá thì phải khai 40 dòng cho một mức giá.
     *
     * <p>{@code innerRadius} ở đây là <b>bán kính bàn</b> — khoảng cách từ tâm bàn tới ghế. Dùng
     * lại đúng cột ấy chứ không thêm cột mới: hai hình không bao giờ cùng tồn tại trên một khu,
     * nên một cột "bán kính" phục vụ được cả hai, và schema không phình ra theo số hình.
     *
     * <p>Các bàn tự xếp thành lưới vuông vắn nhất có thể ({@code ceil(sqrt(số bàn))} bàn mỗi hàng).
     * Không có cột nào khai "mấy bàn một hàng" vì con số ấy suy ra được, và một cột suy ra được là
     * một cột sẽ có ngày mâu thuẫn với phần còn lại.
     */
    TABLE
}
