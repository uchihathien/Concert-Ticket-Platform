import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@nexaticket/tokens/tokens.css';
import '@nexaticket/tokens/reset.css';

export const metadata: Metadata = {
  title: 'NexaTicket — Soát vé',
  description: 'Soát vé',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="vi" data-app="scanner">
      <body>{children}</body>
    </html>
  );
}
