'use client';

import { Moon, Sun } from 'lucide-react';
import { useTheme } from './ThemeProvider';

interface HeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
}

function Header({ title, subtitle, actions }: HeaderProps) {
  const { theme, toggle } = useTheme();

  return (
    <div className="mb-6 flex items-start justify-between">
      <div>
        <h1 className="text-2xl font-bold text-slate-100 dark:text-slate-100">{title}</h1>
        {subtitle && (
          <p className="mt-1 text-sm text-slate-400 dark:text-slate-400">{subtitle}</p>
        )}
      </div>

      <div className="flex items-center gap-3">
        {actions}

        <button
          onClick={toggle}
          aria-label={theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'}
          className={[
            'rounded-md p-2 transition-colors focus:outline-none focus:ring-2',
            'focus:ring-indigo-500 focus:ring-offset-2 focus:ring-offset-slate-900',
            'text-slate-400 hover:bg-slate-700 hover:text-slate-200',
            'dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-slate-200',
          ].join(' ')}
        >
          {theme === 'dark' ? (
            <Sun className="h-5 w-5" aria-hidden="true" />
          ) : (
            <Moon className="h-5 w-5" aria-hidden="true" />
          )}
        </button>
      </div>
    </div>
  );
}

export { Header };
