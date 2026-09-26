import { ClientOnlyApplication } from "./client";

/**
 * One exported shell is served for every browser route by Nginx. React Router
 * then resolves the current URL, including project IDs that do not exist at build time.
 */
export function generateStaticParams() {
  return [{ slug: [] }];
}

export default function ApplicationPage() {
  return <ClientOnlyApplication />;
}
