'use client';
import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Sidebar } from '@/components/layout/Sidebar';
import { MobileNav } from '@/components/layout/MobileNav';
import { ThemeProvider } from '@/components/layout/ThemeProvider';
import { MainContent } from '@/components/layout/MainContent';
import './globals.css';

/**
 * 첫 페인트 전에 <html> 의 dark 클래스를 확정하는 블로킹 스크립트.
 * React 가 마운트된 뒤에 테마를 적용하면 라이트 모드 사용자에게 다크 화면이
 * 한 프레임 보인다. 파싱을 막는 인라인 스크립트라야 그 깜빡임이 사라진다.
 * 기본값은 ThemeProvider 와 동일하게 dark 다.
 */
const THEME_INIT_SCRIPT = `
(function () {
  try {
    var t = localStorage.getItem('theme');
    if (t !== 'light' && t !== 'dark') t = 'dark';
    document.documentElement.classList.toggle('dark', t === 'dark');
  } catch (e) {
    document.documentElement.classList.add('dark');
  }
})();
`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLoginPage = pathname === '/login';
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  }));
  const [mockReady, setMockReady] = useState(
    process.env.NODE_ENV !== 'development' || process.env.NEXT_PUBLIC_USE_MOCK !== 'true'
  );

  useEffect(() => {
    if (process.env.NODE_ENV === 'development' && process.env.NEXT_PUBLIC_USE_MOCK === 'true') {
      import('@/mocks/browser').then(({ worker }) => {
        worker.start({ onUnhandledRequest: 'bypass' }).then(() => {
          setMockReady(true);
        });
      });
    }
  }, []);

  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 font-sans antialiased transition-colors">
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
        <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          {mockReady ? (
            isLoginPage ? children : (
              <div className="flex min-h-screen">
                <Sidebar />
                <MobileNav />
                <MainContent>{children}</MainContent>
              </div>
            )
          ) : (
            <div className="flex h-screen w-full items-center justify-center bg-slate-50">
              <div className="flex flex-col items-center gap-4">
                <div className="w-8 h-8 rounded-full border-4 border-indigo-500 border-t-transparent animate-spin"></div>
                <div className="text-slate-500 font-medium">Initializing MSW...</div>
              </div>
            </div>
          )}
        </QueryClientProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
