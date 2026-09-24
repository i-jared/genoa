"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "Overview" },
  { href: "/reports", label: "Reports" },
  { href: "/users", label: "Users" },
  { href: "/jobs", label: "Failed jobs" },
  { href: "/diagnostics", label: "Diagnostics" },
  { href: "/audit", label: "Audit log" },
];

export function NavLinks() {
  const pathname = usePathname();
  return (
    <nav aria-label="Admin sections" className="nav">
      {LINKS.map(({ href, label }) => {
        const active = href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
        return (
          <Link key={href} href={href} className="nav-link" aria-current={active ? "page" : undefined}>
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
