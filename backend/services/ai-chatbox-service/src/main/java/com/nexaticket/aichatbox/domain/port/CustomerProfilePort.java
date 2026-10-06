// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.CustomerProfile;

/**
 * Hồ sơ của <b>chính người đang chat</b>, tra ở identity-service bằng token của họ.
 *
 * <p>Tách khỏi {@link IdentityLookupPort} dù cùng gọi một service, vì hai cổng đi hai đường có mô
 * hình tin cậy khác nhau. Cổng kia tra tên <i>người trực</i> qua đường nội bộ và chỉ để hiển thị;
 * cổng này tra hồ sơ của <i>khách</i> qua {@code GET /v1/me} — endpoint không nhận id, nên dù mô
 * hình sinh ra tham số gì cũng không đọc được hồ sơ của người khác. Gộp hai cổng là mời một ngày
 * nào đó đường nội bộ nhận một userId do mô hình đoán.
 */
public interface CustomerProfilePort {

    /**
     * @param callerAccessToken bearer token của người đang chat
     * @throws RemoteCallException identity-service quá hạn, từ chối, hoặc trả 5xx
     */
    CustomerProfile currentProfile(String callerAccessToken);
}
