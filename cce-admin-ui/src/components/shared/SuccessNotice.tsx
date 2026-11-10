export function SuccessNotice({ message }: { message: string }) {
  return (
    <p
      role="status"
      className="rounded border border-green-300 bg-green-50 p-2 text-sm text-green-800"
    >
      {message}
    </p>
  );
}
