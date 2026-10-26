'use client';

import React, { createContext, useContext, useCallback, useEffect, useState, useSyncExternalStore } from 'react';

type Theme = 'light' | 'dark' | 'system';

const THEME_STORAGE_KEY = 'theme';

/** Listeners notified when the persisted theme changes. */
const themeListeners = new Set<() => void>();

function readPersistedTheme(): Theme {
  if (typeof window === 'undefined') return 'system';
  const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
  return saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system';
}

function persistTheme(theme: Theme): void {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  themeListeners.forEach(listener => listener());
}

function subscribeToTheme(listener: () => void): () => void {
  themeListeners.add(listener);
  return () => {
    themeListeners.delete(listener);
  };
}

interface ThemeContextType {
  theme: Theme;
  setTheme: (theme: Theme) => void;
  actualTheme: 'light' | 'dark';
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const theme = useSyncExternalStore<Theme>(subscribeToTheme, readPersistedTheme, () => 'system');
  const setTheme = useCallback((next: Theme) => {
    persistTheme(next);
  }, []);
  const [actualTheme, setActualTheme] = useState<'light' | 'dark'>('dark');
  // `true` once the component has hydrated on the client. `useSyncExternalStore` is used
  // instead of a `mounted` state + effect so that no extra render is needed.
  const mounted = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false
  );
  const transitionRef = React.useRef<ViewTransition | null>(null);

  useEffect(() => {
    if (!mounted) return;

    const applyTheme = (newTheme?: 'light' | 'dark') => {
      const themeToApply: 'light' | 'dark' = newTheme || (theme === 'system' 
        ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
        : theme === 'light' ? 'light' : 'dark');
      
      setActualTheme(themeToApply);
      document.documentElement.classList.toggle('dark', themeToApply === 'dark');
    };

    const applyThemeWithTransition = () => {
      // Abort previous transition if it exists
      if (transitionRef.current) {
        try {
          transitionRef.current.skipTransition();
        } catch (e) {
          // Ignore if transition already finished
        }
      }

      // Check if document is visible - skip transition if hidden
      if (document.hidden) {
        applyTheme();
        return;
      }

      // Check if View Transition API is supported
      if (typeof document.startViewTransition === 'function') {
        try {
          transitionRef.current = document.startViewTransition(() => {
            applyTheme();
          });
          
          // Clear ref after transition completes or fails
          if (transitionRef.current) {
            transitionRef.current.finished
              .then(() => { transitionRef.current = null; })
              .catch((error: Error) => { 
                // Silently handle transition errors (visibility changes, etc.)
                transitionRef.current = null;
              });
          }
        } catch (error) {
          // Fallback if transition fails to start
          applyTheme();
        }
      } else {
        // Fallback for browsers that don't support View Transition API
        applyTheme();
      }
    };

    applyThemeWithTransition();
    localStorage.setItem('theme', theme);

    // Listen for system theme changes
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handleSystemThemeChange = () => {
      if (theme === 'system') {
        applyThemeWithTransition();
      }
    };
    
    // Listen for visibility changes to abort transitions
    const handleVisibilityChange = () => {
      if (document.hidden && transitionRef.current) {
        try {
          transitionRef.current.skipTransition();
        } catch (e) {
          // Ignore
        }
        transitionRef.current = null;
      }
    };
    
    mediaQuery.addEventListener('change', handleSystemThemeChange);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    
    return () => {
      mediaQuery.removeEventListener('change', handleSystemThemeChange);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      // Abort any pending transition on unmount
      if (transitionRef.current) {
        try {
          transitionRef.current.skipTransition();
        } catch (e) {
          // Ignore
        }
      }
    };
  }, [theme, mounted]);

  return (
    <ThemeContext.Provider value={{ theme, setTheme, actualTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within ThemeProvider');
  }
  return context;
}
