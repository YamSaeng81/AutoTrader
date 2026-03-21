'use client';
import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Sidebar } from '@/components/layout/Sidebar';
import { ThemeProvider } from '@/components/layout/ThemeProvider';
import { MainContent } from '@/components/layout/MainContent';
import './globals.css';

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
        <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          {mockReady ? (
            isLoginPage ? children : (
              <div className="flex min-h-screen">
                <Sidebar />
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
