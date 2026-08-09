import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "請求書仕訳AI",
  description: "請求書PDFから仕訳案を生成するデモ（ポートフォリオ）",
  robots: { index: false, follow: false },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
