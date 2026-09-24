import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Genoa admin", template: "%s · Genoa admin" },
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f5f3ee" },
    { media: "(prefers-color-scheme: dark)", color: "#17251f" },
  ],
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Reading headers keeps every page dynamic so Next can apply the per-request
  // CSP nonce from proxy.ts to its scripts.
  await headers();
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
