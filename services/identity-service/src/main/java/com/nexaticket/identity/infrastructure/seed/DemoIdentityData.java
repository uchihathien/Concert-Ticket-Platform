// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.seed;

import static com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.InviteState.ACCEPTED;
import static com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.InviteState.EXPIRED;
import static com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.InviteState.PENDING;
import static com.nexaticket.kernel.access.Role.CHECKIN_STAFF;
import static com.nexaticket.kernel.access.Role.EVENT_MANAGER;
import static com.nexaticket.kernel.access.Role.ORG_ADMIN;
import static com.nexaticket.kernel.access.Role.ORG_OWNER;

import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository.Limits;
import com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.InviteSpec;
import com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.MemberSpec;
import com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.OrgSpec;
import com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.PersonSpec;
import com.nexaticket.identity.infrastructure.seed.DemoIdentitySeeder.ProfileSpec;
import java.util.List;

/**
 * Nội dung của dữ liệu danh tính mẫu.
 *
 * <p>Tách khỏi {@link DemoIdentitySeeder} vì đây là dữ liệu, không phải logic — cùng lý do với
 * {@code DemoData} bên catalog-service.
 *
 * <p>Chọn có chủ đích để mọi màn hình quản trị đều có thứ để hiển thị:
 *
 * <ul>
 *   <li><b>Đủ bốn vai trò trong cùng một tổ chức.</b> Bảng thành viên là màn hình duy nhất mà cả
 *       bốn cùng xuất hiện; thiếu một vai trò là thiếu một dòng để so sánh quyền.
 *   <li><b>Nhiều tổ chức, kích thước khác nhau</b> — từ sáu người tới hai người. Trang nền tảng của
 *       superadmin sắp xếp và phân trang theo danh sách này.
 *   <li><b>Một tổ chức bị khoá.</b> {@code SUSPENDED} đi một nhánh riêng ở mọi màn hình, và một
 *       danh sách toàn {@code ACTIVE} không cho biết nhánh đó có chạy hay không.
 *   <li><b>Đủ ba trạng thái lời mời</b>: đang chờ, hết hạn, đã nhận.
 *   <li><b>Có tổ chức khai trần mua vé và có tổ chức để trống.</b> Để trống nghĩa là kế thừa trần
 *       nền tảng, không phải "không giới hạn" (ADR-1014) — phân biệt đó chỉ nhìn thấy khi có cả hai.
 * </ul>
 *
 * <p>Email dùng miền {@code demo.nexaticket.vn}: không phải miền thật, nên không có nguy cơ một
 * lời mời mẫu đi tới hộp thư của người thật, và nhìn địa chỉ là biết ngay dòng nào là dữ liệu ảo.
 */
final class DemoIdentityData {

    private DemoIdentityData() {}

    private static final String MAIL = "@demo.nexaticket.vn";

    // -----------------------------------------------------------------------
    // Người dùng
    // -----------------------------------------------------------------------
    static final List<PersonSpec> PEOPLE = List.of(
            new PersonSpec("an-nguyen", "Nguyễn Hoàng An", "an.nguyen" + MAIL, "0901 234 501"),
            new PersonSpec("binh-tran", "Trần Thanh Bình", "binh.tran" + MAIL, "0901 234 502"),
            new PersonSpec("chi-le", "Lê Ngọc Chi", "chi.le" + MAIL, "0901 234 503"),
            new PersonSpec("dung-pham", "Phạm Tiến Dũng", "dung.pham" + MAIL, "0901 234 504"),
            new PersonSpec("giang-vo", "Võ Thu Giang", "giang.vo" + MAIL, "0901 234 505"),
            new PersonSpec("hai-do", "Đỗ Minh Hải", "hai.do" + MAIL, "0901 234 506"),
            new PersonSpec("khanh-bui", "Bùi Quốc Khánh", "khanh.bui" + MAIL, "0901 234 507"),
            new PersonSpec("lan-nguyen", "Nguyễn Thị Lan", "lan.nguyen" + MAIL, "0901 234 508"),
            new PersonSpec("minh-hoang", "Hoàng Nhật Minh", "minh.hoang" + MAIL, "0901 234 509"),
            new PersonSpec("ngan-duong", "Dương Kim Ngân", "ngan.duong" + MAIL, "0901 234 510"),
            new PersonSpec("phuc-vu", "Vũ Hồng Phúc", "phuc.vu" + MAIL, "0901 234 511"),
            new PersonSpec("quyen-ly", "Lý Bảo Quyên", "quyen.ly" + MAIL, "0901 234 512"),
            new PersonSpec("son-dang", "Đặng Trường Sơn", "son.dang" + MAIL, "0901 234 513"),
            new PersonSpec("thao-nguyen", "Nguyễn Phương Thảo", "thao.nguyen" + MAIL, "0901 234 514"),
            new PersonSpec("tuan-cao", "Cao Anh Tuấn", "tuan.cao" + MAIL, "0901 234 515"),
            new PersonSpec("uyen-ho", "Hồ Thục Uyên", "uyen.ho" + MAIL, "0901 234 516"));

    // -----------------------------------------------------------------------
    // Tổ chức
    // -----------------------------------------------------------------------
    static final List<OrgSpec> ORGANIZATIONS = List.of(

            // Tổ chức đứng tên 26 sự kiện mẫu của catalog-service. Cờ ownsCatalogEvents khiến nó
            // nhận đúng id trong nexaticket.catalog.demo-organization-id — đây là mối nối duy nhất
            // giữa dữ liệu mẫu của hai service.
            new OrgSpec(
                    "nexaticket-demo",
                    "Công ty Cổ phần Sự kiện NexaTicket",
                    OrganizationStatus.ACTIVE,
                    true,
                    180,
                    new ProfileSpec(
                            "Công ty Cổ phần Sự kiện NexaTicket",
                            "0109876543",
                            "Nguyễn Hoàng An",
                            "lienhe" + MAIL,
                            "024 3936 1234",
                            "Tổ chức mẫu, đứng tên toàn bộ sự kiện dựng sẵn."),
                    new Limits(6, 8, 8, 12),
                    List.of(
                            new MemberSpec("an-nguyen", ORG_OWNER),
                            new MemberSpec("binh-tran", ORG_ADMIN),
                            new MemberSpec("chi-le", EVENT_MANAGER),
                            new MemberSpec("dung-pham", EVENT_MANAGER),
                            new MemberSpec("giang-vo", CHECKIN_STAFF),
                            new MemberSpec("hai-do", CHECKIN_STAFF)),
                    List.of(
                            new InviteSpec("ung.vien.1" + MAIL, EVENT_MANAGER, PENDING),
                            new InviteSpec("ung.vien.2" + MAIL, CHECKIN_STAFF, EXPIRED),
                            new InviteSpec("binh.tran.cu" + MAIL, ORG_ADMIN, ACCEPTED))),
            new OrgSpec(
                    "sao-kim-entertainment",
                    "Sao Kim Entertainment",
                    OrganizationStatus.ACTIVE,
                    false,
                    120,
                    new ProfileSpec(
                            "Công ty TNHH Giải trí Sao Kim",
                            "0312345678",
                            "Bùi Quốc Khánh",
                            "info.saokim" + MAIL,
                            "028 3822 5566",
                            null),
                    null, // kế thừa trần nền tảng
                    List.of(
                            new MemberSpec("khanh-bui", ORG_OWNER),
                            new MemberSpec("lan-nguyen", ORG_ADMIN),
                            new MemberSpec("minh-hoang", EVENT_MANAGER)),
                    List.of(new InviteSpec("tuyendung.saokim" + MAIL, CHECKIN_STAFF, PENDING))),
            new OrgSpec(
                    "nghe-thuat-dong-do",
                    "Trung tâm Nghệ thuật Đông Đô",
                    OrganizationStatus.ACTIVE,
                    false,
                    95,
                    new ProfileSpec(
                            "Trung tâm Nghệ thuật Đông Đô",
                            "0101234567",
                            "Dương Kim Ngân",
                            "vanphong.dongdo" + MAIL,
                            "024 3719 8899",
                            "Đơn vị sự nghiệp, xuất hoá đơn theo quý."),
                    new Limits(4, null, 6, 8),
                    List.of(
                            new MemberSpec("ngan-duong", ORG_OWNER),
                            new MemberSpec("phuc-vu", EVENT_MANAGER),
                            new MemberSpec("quyen-ly", CHECKIN_STAFF)),
                    List.of()),
            new OrgSpec(
                    "viet-sport-promotion",
                    "Việt Sport Promotion",
                    OrganizationStatus.ACTIVE,
                    false,
                    60,
                    new ProfileSpec(
                            "Công ty Cổ phần Xúc tiến Thể thao Việt",
                            "0313579246",
                            "Đặng Trường Sơn",
                            "contact.vsp" + MAIL,
                            "028 3910 4433",
                            null),
                    null,
                    List.of(new MemberSpec("son-dang", ORG_OWNER), new MemberSpec("thao-nguyen", ORG_ADMIN)),
                    List.of(
                            new InviteSpec("dieu.phoi.vsp" + MAIL, EVENT_MANAGER, PENDING),
                            new InviteSpec("soat.ve.vsp" + MAIL, CHECKIN_STAFF, PENDING))),

            // Tổ chức bị khoá: vẫn đăng nhập được, vẫn thấy dữ liệu cũ, nhưng không mở bán được gì.
            new OrgSpec(
                    "su-kien-bien-xanh",
                    "Công ty Sự kiện Biển Xanh",
                    OrganizationStatus.SUSPENDED,
                    false,
                    30,
                    new ProfileSpec(
                            "Công ty TNHH Sự kiện Biển Xanh",
                            "0401122334",
                            "Cao Anh Tuấn",
                            "hotro.bienxanh" + MAIL,
                            "0236 3888 777",
                            "Tạm khoá: hồ sơ pháp nhân chưa hợp lệ."),
                    null,
                    List.of(new MemberSpec("tuan-cao", ORG_OWNER), new MemberSpec("uyen-ho", CHECKIN_STAFF)),
                    List.of()));
}
