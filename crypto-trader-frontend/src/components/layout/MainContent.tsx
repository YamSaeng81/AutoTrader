'use client';

import { useUiStore } from '@/store';
import { cn } from './Sidebar';

interface MainContentProps {
  children: React.ReactNode;
}

export function MainContent({ children }: MainContentProps) {
  const { sidebarCollapsed } = useUiStore();

  return (
    <main
      className={cn(
        'flex-1 min-h-screen outline-none transition-all duration-300',
        sidebarCollapsed ? 'ml-16' : 'ml-64'
      )}
    >
      {children}
    </main>
  );
}
