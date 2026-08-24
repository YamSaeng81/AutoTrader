'use client';

import { createContext, useContext, useEffect, useState } from 'react';

type Theme = 'light' | 'dark';

const ThemeContext = createContext<{
    theme: Theme;
    toggle: () => void;
}>({ theme: 'dark', toggle: () => {} });

export function useTheme() {
    return useContext(ThemeContext);
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
    // 저장된 값을 첫 렌더에서 바로 읽는다. 예전에는 useEffect 안에서 읽어 setTheme 을
    // 호출했는데, 그건 렌더를 한 번 더 돌게 하고 react-hooks/set-state-in-effect 에도 걸렸다.
    // (화면 깜빡임까지 유발하지는 않았다 — e2e 뮤테이션으로 확인함. 첫 페인트를 막는 건
    //  app/layout.tsx 의 블로킹 인라인 스크립트 쪽이다.)
    const [theme, setTheme] = useState<Theme>(() => {
        if (typeof window === 'undefined') return 'dark';
        const stored = localStorage.getItem('theme');
        return stored === 'light' || stored === 'dark' ? stored : 'dark';
    });

    useEffect(() => {
        const root = document.documentElement;
        if (theme === 'dark') {
            root.classList.add('dark');
        } else {
            root.classList.remove('dark');
        }
        localStorage.setItem('theme', theme);
    }, [theme]);

    const toggle = () => setTheme(prev => (prev === 'dark' ? 'light' : 'dark'));

    return (
        <ThemeContext.Provider value={{ theme, toggle }}>
            {children}
        </ThemeContext.Provider>
    );
}
