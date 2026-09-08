import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@nexaticket/tokens/tokens.css';
import '@nexaticket/tokens/reset.css';

export const metadata: Metadata = {
  title: 'NexaTicket — Quản lý tổ chức',
  description: 'Quản lý tổ chức',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
