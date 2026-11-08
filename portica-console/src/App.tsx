import { lazy, Suspense, useState } from 'react';
import { Skeleton } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, Navigate, RouterProvider, type RouteObject } from 'react-router-dom';
import { ApiError } from './api/client';
import { AuthProvider } from './auth/AuthProvider';
import { CALLBACK_PATH } from './auth/oidc';
import { Home, NotFound, OrgShell } from './app/Shell';
import { ThemeProvider } from './app/ThemeProvider';
import { AppDetail, AppList } from './pages/apps/Apps';
import { AuditLog } from './pages/audit/AuditLog';
import { Dashboard } from './pages/dashboard/Dashboard';
import { DeveloperDetail, DeveloperList } from './pages/developers/Developers';
import { Environments } from './pages/environments/Environments';
import { KeyValueMaps } from './pages/kvms/KeyValueMaps';
import { Members } from './pages/members/Members';
import { Products } from './pages/products/Products';
import { ProxyCreate } from './pages/proxies/ProxyCreate';
import { ProxyDetail } from './pages/proxies/ProxyDetail';
import { ProxyList } from './pages/proxies/ProxyList';
import { SharedFlowDetail, SharedFlowList } from './pages/sharedflows/SharedFlows';
import { TargetServers } from './pages/targetservers/TargetServers';

// ECharts is large: load the analytics page (and its charts) only when it's opened
const Analytics = lazy(() => import('./pages/analytics/Analytics').then((m) => ({ default: m.Analytics })));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: false,
      // Don't retry client errors (403, 404 ...): they won't change.
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
});

const routes: RouteObject[] = [
  { path: '/', element: <Home /> },
  { path: CALLBACK_PATH, element: <Navigate to="/" replace /> },
  {
    path: '/o/:org',
    element: <OrgShell />,
    children: [
      { index: true, element: <Navigate to="dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'analytics', element: <Suspense fallback={<Skeleton active paragraph={{ rows: 8 }} />}><Analytics /></Suspense> },
      { path: 'audit', element: <AuditLog /> },
      { path: 'proxies', element: <ProxyList /> },
      { path: 'proxies/new', element: <ProxyCreate /> },
      { path: 'proxies/:api', element: <ProxyDetail /> },
      { path: 'sharedflows', element: <SharedFlowList /> },
      { path: 'sharedflows/:name', element: <SharedFlowDetail /> },
      { path: 'products', element: <Products /> },
      { path: 'developers', element: <DeveloperList /> },
      { path: 'developers/:email', element: <DeveloperDetail /> },
      { path: 'apps', element: <AppList /> },
      { path: 'apps/:email/:app', element: <AppDetail /> },
      { path: 'environments', element: <Environments /> },
      { path: 'target-servers', element: <TargetServers /> },
      { path: 'kvms', element: <KeyValueMaps /> },
      { path: 'members', element: <Members /> },
      { path: '*', element: <NotFound /> },
    ],
  },
  { path: '*', element: <NotFound /> },
];

/** Created after sign-in, so it starts from the URL the OIDC callback restored. */
function Router() {
  const [router] = useState(() => createBrowserRouter(routes));
  return <RouterProvider router={router} />;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <Router />
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
