// The signed-in shell (F-03): static sidebar, header from `GET /me`, and the page. A failing
// `/me` shows its error in the header only; there is no global error page (F-04).
import { Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';
import { Header } from '../components/layout/Header';
import { Sidebar } from '../components/layout/Sidebar';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { useMe } from '../hooks/useMe';
import { describeError } from '../utils/describeError';

export function AppLayout() {
  const me = useMe();
  const { logout } = useAuth();
  return (
    <div className="flex min-h-screen bg-gray-50 text-gray-900">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center gap-4 border-b border-gray-200 bg-white px-6 py-3">
          {/* DESIGN-GAP: item 81 shows LoadingSpinner "in the header", but Header's props (item
              58) have no loading slot; the spinner sits in the same top bar, before Header. */}
          {me.isPending && <LoadingSpinner />}
          <Header
            username={me.data?.username}
            roles={me.data?.roles}
            error={me.isError ? describeError(me.error) : undefined}
            onSignOut={() => void logout()}
          />
        </div>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
