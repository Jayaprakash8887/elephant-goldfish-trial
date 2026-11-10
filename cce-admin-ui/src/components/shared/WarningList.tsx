export function WarningList({ messages }: { messages: string[] }) {
  if (messages.length === 0) return null;
  return (
    <ul
      role="status"
      className="list-inside list-disc rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
    >
      {messages.map((message) => (
        <li key={message}>{message}</li>
      ))}
    </ul>
  );
}
