import { Link } from 'react-router';

export function NotFoundPanel({
  message,
  backTo,
  backLabel,
}: {
  message: string;
  backTo: string;
  backLabel: string;
}) {
  return (
    <div className="space-y-2 rounded border border-gray-300 bg-white p-4">
      <p>{message}</p>
      <Link to={backTo} className="text-blue-700 underline">
        {backLabel}
      </Link>
    </div>
  );
}
