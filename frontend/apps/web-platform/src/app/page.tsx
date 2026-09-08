/**
 * Khung app. Màn hình thật dựng ở G1 trở đi theo
 * docs/architecture-v2/plan/frontend.md §12.
 */
export default function Page() {
  return (
    <main style={{ padding: 48, maxWidth: 720 }}>
      <h1 style={{ color: 'var(--nt-primary)', marginTop: 0 }}>NexaTicket · Quản trị nền tảng</h1>
      <p style={{ color: 'var(--nt-text-muted)' }}>Tổ chức, địa điểm dùng chung, sổ cái, đối soát, chi trả. Màn hình dựng ở G0–G5.</p>
    </main>
  );
}
