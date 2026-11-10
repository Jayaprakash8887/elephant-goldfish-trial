// Rendered by bootstrap before the router exists. It is not a route and makes no API call.
export function StartupErrorScreen({ title, lines }: { title: string; lines: string[] }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 p-6 text-gray-900">
      <div className="max-w-lg rounded border border-red-300 bg-white p-6">
        <h1 className="mb-3 text-xl font-semibold">{title}</h1>
        {lines.map((line) => (
          <p key={line} className="text-sm">
            {line}
          </p>
        ))}
      </div>
    </div>
  );
}
