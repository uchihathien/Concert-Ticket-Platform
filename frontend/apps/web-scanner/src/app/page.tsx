/**
 * Khung app. Màn hình thật dựng ở G1 trở đi theo
 * docs/architecture-v2/plan/frontend.md §12.
 */
export default function Page() {
  return (
    <main style={{ padding: 48, maxWidth: 720 }}>
      <h1 style={{ color: 'var(--nt-primary)', marginTop: 0 }}>NexaTicket · Soát vé</h1>
      <p style={{ color: 'var(--nt-text-muted)' }}>Nền tối có lý do: dùng ngoài trời buổi tối ở cửa soát vé. Màn hình dựng ở G6.</p>
    </main>
  );
}
