import { useEffect } from 'react';
import { App } from 'antd';
import { useBlocker } from 'react-router-dom';

/** Asks before leaving the page (in-app navigation or closing the tab) while {@code dirty}. */
export function useUnsavedChanges(dirty: boolean) {
  const { modal } = App.useApp();
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    dirty && currentLocation.pathname !== nextLocation.pathname);

  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    modal.confirm({
      title: 'Discard unsaved changes?',
      content: 'Your edits haven’t been saved as a revision yet.',
      okText: 'Discard',
      okButtonProps: { danger: true },
      cancelText: 'Keep editing',
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
  }, [blocker, modal]);

  useEffect(() => {
    if (!dirty) return undefined;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);
}
