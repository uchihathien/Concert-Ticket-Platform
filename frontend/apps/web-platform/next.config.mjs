/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ['@nexaticket/tokens'],
  // Access token KHÔNG BAO GIỜ chạm localStorage/sessionStorage (plan/frontend.md §4).
  // Session nằm ở cookie httpOnly do route handler quản lý.
  poweredByHeader: false,
};

export default nextConfig;
