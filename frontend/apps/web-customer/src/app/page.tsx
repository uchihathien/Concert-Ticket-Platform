import styles from './page.module.css';

/**
 * C-HOME — khung bố cục theo ui-direction.md §4.
 *
 * Thứ tự cố ý: header ưu tiên tìm kiếm → hero → chip thể loại → dải sự kiện cuộn ngang.
 * Nội dung đặt TRƯỚC thương hiệu, khác v1 vốn dành nguyên màn đầu cho brand hero.
 *
 * G0 chỉ dựng khung tĩnh; dữ liệu thật nối ở G1 khi catalog-service sẵn sàng.
 */
export default function HomePage() {
  return (
    <>
      <header className={styles.header}>
        <a className={styles.brand} href="/">
          NexaTicket
        </a>
        <form className={styles.search} role="search" action="/events">
          <input
            className={styles.searchInput}
            type="search"
            name="query"
            placeholder="Tìm sự kiện, nghệ sĩ…"
            aria-label="Tìm sự kiện"
          />
        </form>
        <nav className={styles.nav}>
          <a href="/organizer">Tạo sự kiện</a>
          <a href="/me/tickets">Vé của tôi</a>
          <a className={styles.login} href="/login">
            Đăng nhập
          </a>
        </nav>
      </header>

      <main>
        <section className={styles.hero} aria-label="Sự kiện nổi bật">
          <div className={styles.heroPlaceholder}>Băng rôn sự kiện</div>
        </section>

        <nav className={styles.chips} aria-label="Thể loại">
          {['Tất cả', 'Nhạc sống', 'Sân khấu', 'Thể thao', 'Hội thảo'].map((label, i) => (
            <button key={label} className={styles.chip} aria-pressed={i === 0} type="button">
              {label}
            </button>
          ))}
        </nav>

        {['Đang bán chạy', 'Sắp diễn ra', 'Cuối tuần này'].map((title) => (
          <section key={title} className={styles.row}>
            <div className={styles.rowHead}>
              <h2 className={styles.rowTitle}>{title}</h2>
              <a href="/events">Xem tất cả</a>
            </div>
            <div className={styles.carousel}>
              {Array.from({ length: 6 }, (_, i) => (
                <article key={i} className={styles.card}>
                  <div className={styles.cardImage} />
                  <h3 className={styles.cardTitle}>Tên sự kiện</h3>
                  <p className={styles.cardMeta}>01/11/2026 · Hà Nội</p>
                  <p className={styles.cardPrice}>Từ 500.000 ₫</p>
                </article>
              ))}
            </div>
          </section>
        ))}
      </main>

      <footer className={styles.footer}>
        <a href="/terms">Điều khoản</a>
        <a href="/privacy">Bảo mật</a>
        <a href="/support">Hỗ trợ</a>
      </footer>
    </>
  );
}
