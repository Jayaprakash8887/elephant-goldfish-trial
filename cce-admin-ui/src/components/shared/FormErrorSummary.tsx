/** The server messages that belong to no field of the form; rendered only when there is one. */
export function FormErrorSummary({ messages }: { messages: string[] }) {
  if (messages.length === 0) return null;
  return (
    <div role="alert" className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-800">
      {messages.map((message) => (
        <p key={message}>{message}</p>
      ))}
    </div>
  );
}
