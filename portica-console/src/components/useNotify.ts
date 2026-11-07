import { App } from 'antd';
import { errorMessage } from '../api/client';

/** Success and error toasts that understand API problem details. */
export function useNotify() {
  const { message, notification } = App.useApp();
  return {
    success: (text: string) => void message.success(text),
    error: (error: unknown, title = 'That didn’t work') =>
      notification.error({ message: title, description: errorMessage(error), duration: 8 }),
  };
}
