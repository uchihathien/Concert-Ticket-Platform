// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Vị trí của một khu trên mặt bằng, và công thức sinh toạ độ từng ghế.
 *
 * <h3>Đơn vị là "ghế", không phải pixel</h3>
 *
 * <p>Một đơn vị = khoảng cách giữa hai ghế cạnh nhau. Không có pixel, không có mét, không có tỷ lệ
 * thu phóng ở đây. Lý do: cùng một sơ đồ được vẽ trên điện thoại 360px, trên màn hình quản trị
 * 1600px và trong ảnh poster 2480px, và con số duy nhất đúng ở cả ba chỗ là con số không mang đơn
 * vị hiển thị nào. Frontend tính {@code viewBox} từ bao hình rồi để SVG lo phần co giãn.
 *
 * <h3>Vì sao toạ độ nằm ở Catalog chứ không ở Inventory</h3>
 *
 * <p>Hình dạng khán phòng là dữ liệu của địa điểm: nó không đổi khi bán hết vé, và nó giống nhau ở
 * mọi suất diễn. Inventory <b>nhận</b> toạ độ đã tính sẵn của từng ghế qua {@code session.published}
 * và chỉ lưu lại; nó không bao giờ phải biết "khu này là cung tròn hay chữ nhật". Đặt công thức ở
 * đây giữ cho đường giữ chỗ — thứ chạy 10k lần/giây — không có một phép lượng giác nào.
 *
 * @param rotationDeg góc xoay cả khu; có nghĩa với {@link LayoutShape#GRID} và
 *     {@link LayoutShape#TABLE}
 * @param innerRadius với {@link LayoutShape#ARC} là bán kính hàng đầu tiên; với
 *     {@link LayoutShape#TABLE} là bán kính bàn. Không có nghĩa với {@code GRID}.
 * @param startAngleDeg góc mép trái của cung, độ, 0° là hướng +x và góc tăng theo chiều kim đồng hồ
 *     (trục y hướng xuống, đúng quy ước của SVG). Chỉ có nghĩa với {@code ARC}.
 */
public record ZoneLayout(
        LayoutShape shape,
        double originX,
        double originY,
        double rotationDeg,
        double innerRadius,
        double startAngleDeg,
        double endAngleDeg) {

    /** Khoảng cách giữa hai hàng, tính theo đơn vị ghế. Hàng thưa hơn ghế vì người còn phải đi lại. */
    public static final double ROW_PITCH = 1.4;

    public ZoneLayout {
        if (shape == null) {
            throw new IllegalArgumentException("Khu phải khai hình dạng bố cục");
        }
        if (shape == LayoutShape.TABLE && innerRadius <= 0) {
            throw new IllegalArgumentException("Khu bàn tròn phải có bán kính bàn > 0");
        }
        if (shape == LayoutShape.ARC) {
            if (innerRadius <= 0) {
                throw new IllegalArgumentException("Khu cung phải có bán kính trong > 0");
            }
            if (endAngleDeg <= startAngleDeg) {
                throw new IllegalArgumentException("Khu cung phải có góc kết thúc lớn hơn góc bắt đầu");
            }
            if (endAngleDeg - startAngleDeg > 360) {
                throw new IllegalArgumentException("Khu cung không quét quá một vòng");
            }
        }
    }

    public static ZoneLayout grid(double originX, double originY, double rotationDeg) {
        return new ZoneLayout(LayoutShape.GRID, originX, originY, rotationDeg, 0, 0, 0);
    }

    public static ZoneLayout arc(
            double centerX, double centerY, double innerRadius, double startAngleDeg, double endAngleDeg) {
        return new ZoneLayout(LayoutShape.ARC, centerX, centerY, 0, innerRadius, startAngleDeg, endAngleDeg);
    }

    /** @param tableRadius khoảng cách từ tâm bàn tới ghế, theo đơn vị ghế */
    public static ZoneLayout tables(double originX, double originY, double tableRadius, double rotationDeg) {
        return new ZoneLayout(LayoutShape.TABLE, originX, originY, rotationDeg, tableRadius, 0, 0);
    }

    /**
     * Toạ độ của một ghế cụ thể.
     *
     * @param row 1-based, hàng 1 là hàng gần sân khấu nhất
     * @param seat 1-based, đánh từ trái sang phải khi nhìn từ phía khán giả
     */
    public Point seatPosition(int row, int seat, int rowCount, int seatsPerRow) {
        return switch (shape) {
            case ARC -> arcPosition(row, seat, seatsPerRow);
            case TABLE -> tablePosition(row, seat, rowCount, seatsPerRow);
            case GRID -> gridPosition(row, seat, seatsPerRow);
        };
    }

    /** Lối đi giữa hai bàn, theo đơn vị ghế. Người phải đi lọt giữa hai lưng ghế quay vào nhau. */
    private static final double TABLE_AISLE = 2.0;

    /** Số bàn trên một hàng: lưới vuông vắn nhất chứa đủ số bàn. */
    private static int tablesPerRow(int tableCount) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(tableCount)));
    }

    /**
     * Ghế thứ {@code seat} quanh bàn thứ {@code row}.
     *
     * <p>Ghế số 1 nằm ở phía <b>xa sân khấu nhất</b> của bàn rồi đi ngược chiều kim đồng hồ. Quy
     * ước ấy để khi nhân viên đọc "bàn 3 ghế 1" thì người ngồi đó tìm được mình mà không phải đoán
     * bàn đang xoay kiểu gì — ghế 1 luôn là ghế quay lưng về sân khấu.
     */
    private Point tablePosition(int row, int seat, int rowCount, int seatsPerRow) {
        int perRow = tablesPerRow(rowCount);
        int index = row - 1;
        double pitch = 2 * innerRadius + TABLE_AISLE;

        double tableX = (index % perRow - (perRow - 1) / 2.0) * pitch;
        double tableY = (index / perRow) * pitch;

        // Trục y hướng xuống (quy ước SVG), nên -y là hướng RA XA sân khấu khi sân khấu ở phía trên.
        double angle = 2 * Math.PI * (seat - 1) / seatsPerRow;
        double localX = tableX + innerRadius * Math.sin(angle);
        double localY = tableY - innerRadius * Math.cos(angle);

        return rotate(localX, localY);
    }

    /** Xoay quanh gốc của khu rồi dời về vị trí của khu. Dùng chung cho GRID và TABLE. */
    private Point rotate(double localX, double localY) {
        double radians = Math.toRadians(rotationDeg);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Point(originX + localX * cos - localY * sin, originY + localX * sin + localY * cos);
    }

    /** Hàng nằm ngang, khu được căn giữa quanh gốc rồi xoay quanh chính gốc đó. */
    private Point gridPosition(int row, int seat, int seatsPerRow) {
        return rotate(seat - (seatsPerRow + 1) / 2.0, (row - 1) * ROW_PITCH);
    }

    /**
     * Ghế nằm trên cung bán kính {@code innerRadius + (row-1) × ROW_PITCH}, rải đều theo góc.
     *
     * <p>Dùng {@code seat - 0.5} chứ không phải {@code seat - 1}: nó đặt ghế vào <b>giữa</b> ô góc
     * của nó, nên hàng ghế được căn giữa trong cung thay vì dính sát mép trái và hụt một ô ở mép
     * phải.
     */
    private Point arcPosition(int row, int seat, int seatsPerRow) {
        double radius = innerRadius + (row - 1) * ROW_PITCH;
        double sweep = endAngleDeg - startAngleDeg;
        double angle = Math.toRadians(startAngleDeg + sweep * (seat - 0.5) / seatsPerRow);

        return new Point(originX + radius * Math.cos(angle), originY + radius * Math.sin(angle));
    }

    /**
     * Đường bao của khu — đa giác để frontend tô nền và bắt sự kiện chuột cho cả khu.
     *
     * <p>Đây là thứ cho phép màn hình phản hồi ở mức khu khi chưa phóng to tới mức thấy từng ghế:
     * 5.000 ghế thì 5.000 vùng bắt chuột là quá nhiều, còn 3 đa giác thì không.
     */
    public List<Point> outline(int rowCount, int seatsPerRow) {
        return switch (shape) {
            case ARC -> arcOutline(rowCount, seatsPerRow);
            case TABLE -> tableOutline(rowCount);
            case GRID -> gridOutline(rowCount, seatsPerRow);
        };
    }

    /**
     * Chữ nhật bao cả khối bàn, nới nửa lối đi mỗi phía.
     *
     * <p>Bao theo <b>khối</b> chứ không vẽ từng bàn: đường bao tồn tại để bắt sự kiện chuột ở mức
     * khu khi chưa phóng to tới mức thấy từng ghế. Vẽ 40 hình tròn ở mức ấy là 40 vùng bắt chuột
     * cho một thứ người dùng đang nhìn như một mảng duy nhất.
     */
    private List<Point> tableOutline(int rowCount) {
        int perRow = tablesPerRow(rowCount);
        int rows = (int) Math.ceil((double) rowCount / perRow);
        double pitch = 2 * innerRadius + TABLE_AISLE;

        double halfWidth = (perRow - 1) / 2.0 * pitch + innerRadius + TABLE_AISLE / 2;
        double top = -innerRadius - TABLE_AISLE / 2;
        double bottom = (rows - 1) * pitch + innerRadius + TABLE_AISLE / 2;

        List<Point> corners = new ArrayList<>(4);
        for (double[] corner :
                new double[][] {{-halfWidth, top}, {halfWidth, top}, {halfWidth, bottom}, {-halfWidth, bottom}}) {
            corners.add(rotate(corner[0], corner[1]));
        }
        return corners;
    }

    /** Bốn góc của khối chữ nhật, nới nửa ô mỗi phía để ghế mép không nằm đúng trên đường viền. */
    private List<Point> gridOutline(int rowCount, int seatsPerRow) {
        double halfWidth = seatsPerRow / 2.0 + 0.5;
        double top = -ROW_PITCH / 2;
        double bottom = (rowCount - 1) * ROW_PITCH + ROW_PITCH / 2;

        List<Point> corners = new ArrayList<>(4);
        for (double[] corner :
                new double[][] {{-halfWidth, top}, {halfWidth, top}, {halfWidth, bottom}, {-halfWidth, bottom}}) {
            corners.add(rotate(corner[0], corner[1]));
        }
        return corners;
    }

    /**
     * Cung trong đi xuôi, cung ngoài đi ngược — một đa giác kín hình vành khăn cắt.
     *
     * <p>Số điểm lấy theo độ mở của cung: một cung 20° trơn với 4 điểm, một cung 300° lấy 4 điểm sẽ
     * thành hình thoi. Một điểm mỗi 10°, tối thiểu 4.
     */
    private List<Point> arcOutline(int rowCount, int seatsPerRow) {
        double innerR = innerRadius - ROW_PITCH / 2;
        double outerR = innerRadius + (rowCount - 1) * ROW_PITCH + ROW_PITCH / 2;
        double sweep = endAngleDeg - startAngleDeg;
        int steps = Math.max(4, (int) Math.ceil(Math.abs(sweep) / 10));

        List<Point> points = new ArrayList<>(2 * (steps + 1));
        for (int i = 0; i <= steps; i++) {
            points.add(onCircle(innerR, startAngleDeg + sweep * i / steps));
        }
        for (int i = steps; i >= 0; i--) {
            points.add(onCircle(outerR, startAngleDeg + sweep * i / steps));
        }
        return points;
    }

    private Point onCircle(double radius, double angleDeg) {
        double angle = Math.toRadians(angleDeg);
        return new Point(originX + radius * Math.cos(angle), originY + radius * Math.sin(angle));
    }

    /** Một điểm trên mặt bằng. Làm tròn 2 chữ số vì {@code pos_x}/{@code pos_y} là {@code NUMERIC(8,2)}. */
    public record Point(double x, double y) {

        public Point {
            x = Math.round(x * 100) / 100.0;
            y = Math.round(y * 100) / 100.0;
        }
    }
}
