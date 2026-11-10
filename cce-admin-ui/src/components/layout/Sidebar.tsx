// The static sidebar (F-03): the same three links whatever the caller's roles (PRD §2).
import { ShieldCheckIcon, Squares2X2Icon, UsersIcon } from '@heroicons/react/24/outline';
import { NavLink } from 'react-router';

const LINKS = [
  { to: '/users', label: 'Users', Icon: UsersIcon },
  { to: '/roles', label: 'Roles', Icon: ShieldCheckIcon },
  { to: '/menus', label: 'Menus', Icon: Squares2X2Icon },
] as const;

export function Sidebar() {
  return (
    <nav aria-label="Main" className="flex w-56 shrink-0 flex-col gap-1 bg-gray-900 p-4 text-white">
      <p className="mb-4 text-lg font-semibold">CCE Admin</p>
      {LINKS.map(({ to, label, Icon }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) =>
            `flex items-center gap-2 rounded px-2 py-1.5 ${isActive ? 'bg-gray-700' : 'hover:bg-gray-800'}`
          }
        >
          <Icon aria-hidden="true" className="h-5 w-5" />
          {label}
        </NavLink>
      ))}
    </nav>
  );
}
