import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { App as AntApp, ConfigProvider, theme as antTheme } from 'antd';

export type ThemeMode = 'light' | 'dark';

interface ThemeState {
  mode: ThemeMode;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeState>({ mode: 'light', toggle: () => undefined });
const STORAGE_KEY = 'portica.theme';

function initialMode(): ThemeMode {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // storage blocked: follow the OS
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Light and dark mode with Ant Design's algorithms; the choice is remembered per browser. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(initialMode);

  useEffect(() => {
    document.documentElement.dataset.theme = mode;
    document.documentElement.style.colorScheme = mode;
    try {
      window.localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore
    }
  }, [mode]);

  const value = useMemo(() => ({ mode, toggle: () => setMode((m) => (m === 'light' ? 'dark' : 'light')) }), [mode]);

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider
        theme={{
          algorithm: mode === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
          token: { colorPrimary: '#2f54eb', borderRadius: 8, fontFamily: "Inter, 'Segoe UI', system-ui, -apple-system, sans-serif" },
          components: { Layout: { headerHeight: 56 } },
        }}
      >
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </ThemeContext.Provider>
  );
}

export function useThemeMode(): ThemeState {
  return useContext(ThemeContext);
}
