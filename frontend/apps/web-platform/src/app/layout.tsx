import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@nexaticket/tokens/tokens.css';
import '@nexaticket/tokens/reset.css';

export const metadata: Metadata = {
  title: 'NexaTicket — Quản trị nền tảng',
  description: 'Quản trị nền tảng',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
