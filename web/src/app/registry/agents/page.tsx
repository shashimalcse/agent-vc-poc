import { redirect } from "next/navigation";
import { linker } from "@/lib/request";

// The agents list now lives on the Registry home page.
export default async function AgentsIndex() {
  const to = await linker("registry");
  redirect(to("/"));
}
