import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link to="/users" className="text-blue-700 underline">
        Go to users
      </Link>
    </div>
  );
}
