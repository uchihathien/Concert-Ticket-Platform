// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Cách các hàng ghế của một khu nằm trên mặt bằng.
 *
 * <p>Chỉ hai hình, và đó là một lựa chọn chứ không phải chưa làm xong. Mọi khán phòng thật đều dựng
 * được từ hai hình này: sân khấu chữ nhật là các khu {@link #GRID} xếp thẳng, sân khấu tròn là các
 * khu {@link #ARC} quây quanh một tâm, sân khấu chữ U là ba khu — một {@code ARC} ôm đầu sân khấu
 * và hai {@code GRID} xoay 90° chạy dọc hai cánh.
 *
 * <p>Thêm hình thứ ba (đa giác tự do, đường cong Bézier) nghĩa là màn hình khai báo khu phải biến
 * thành một trình vẽ vector, và mã chỗ {@code A-3-12} mất đi nghĩa "hàng 3 ghế 12" mà cả soát vé
 * lẫn hỗ trợ khách hàng đang đọc hàng ngày.
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
    ARC
}
