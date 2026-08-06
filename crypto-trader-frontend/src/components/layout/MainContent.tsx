'use client';

import { useUiStore } from '@/store';
import { cn } from '@/lib/utils';

interface MainContentProps {
  children: React.ReactNode;
}

export function MainContent({ children }: MainContentProps) {
  const { sidebarCollapsed } = useUiStore();

  return (
    <main
      className={cn(
        // overflow-x-clip: 가로 넘침만 잘라내고 스크롤 컨테이너를 만들지 않는다(내부 sticky 유지)
        'flex-1 min-w-0 min-h-screen overflow-x-clip outline-none transition-all duration-300',
        // 페이지 여백은 여기서 일괄 제공한다 — 개별 page.tsx가 p-6/p-8/무패딩으로 제각각이었다.
        'px-4 py-4 sm:px-6 sm:py-6',
        // 모바일: 상단 앱바(h-14) / 하단 탭바(h-14 + 안전영역) 만큼 확보
        'ml-0 pt-[calc(3.5rem+1rem)] pb-[calc(3.5rem+1rem+env(safe-area-inset-bottom))]',
        // 데스크톱: 고정 사이드바 폭만큼 밀고 앱바/탭바 여백은 해제
        'lg:pt-6 lg:pb-6',
        sidebarCollapsed ? 'lg:ml-16' : 'lg:ml-64'
      )}
    >
      {children}
    </main>
  );
}
