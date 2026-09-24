import Link from "next/link";
import { ApiError, requireAdmin } from "@/lib/api";
import { NavLinks } from "@/components/NavLinks";
import { ErrorNotice } from "@/components/ErrorNotice";
import { signOut } from "../sign-in/actions";

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  let email: string;
  try {
    ({ me: { email } } = await requireAdmin());
  } catch (error) {
    // redirect() also throws; only API failures are shown here.
    if (!(error instanceof ApiError)) throw error;
    return (
      <main className="page page-narrow">
        <ErrorNotice title="Couldn’t confirm your admin session" message={error.message} />
        <form action={signOut}>
          <button className="button button-outline" type="submit">Sign out</button>
        </form>
      </main>
    );
  }

  return (
    <>
      <header className="topbar">
        <Link href="/" className="wordmark">Genoa <span>admin</span></Link>
        <NavLinks />
        <div className="whoami">
          <span className="muted" title="Signed in as">{email}</span>
          <form action={signOut}>
            <button className="button button-quiet" type="submit">Sign out</button>
          </form>
        </div>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
