export function ErrorNotice({ message, title = "Couldn’t load this page" }: { message: string; title?: string }) {
  return (
    <div className="notice notice-error" role="alert">
      <strong>{title}</strong>
      <p>{message}</p>
      <p className="muted">Reload the page to try again.</p>
    </div>
  );
}
