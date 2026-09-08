/**
 * Khung app. Màn hình thật dựng ở G1 trở đi theo
 * docs/architecture-v2/plan/frontend.md §12.
 */
export default function Page() {
  return (
    <main style={{ padding: 48, maxWidth: 720 }}>
      <h1 style={{ color: 'var(--nt-primary)', marginTop: 0 }}>NexaTicket · Tổ chức</h1>
      <p style={{ color: 'var(--nt-text-muted)' }}>Địa điểm, thiết kế chỗ ngồi, sự kiện, thành viên. Màn hình dựng ở G1.</p>
    </main>
  );
}
