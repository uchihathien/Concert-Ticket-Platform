# ADR-0012: AI chỉ đọc event bất đồng bộ

**Status:** Accepted

AI/recommendation/forecast chỉ tiêu thụ event đã commit qua pipeline analytics. Không có quyết định checkout, hold, payment hay ticket nào phụ thuộc AI. Bắt đầu bằng content-based recommendation và demand baseline sau khi có dữ liệu consented.
