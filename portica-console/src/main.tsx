import { createRoot } from 'react-dom/client';
import 'antd/dist/reset.css';
import './styles.css';
import { App } from './App';

// No <StrictMode>: its double effects would process the one-time OIDC callback twice.
createRoot(document.getElementById('root') as HTMLElement).render(<App />);
