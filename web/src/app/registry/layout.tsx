import type { Metadata } from "next";
import { RegistryMark } from "@/components/icons";
import { config } from "@/lib/config";
import { linker } from "@/lib/request";

export const metadata: Metadata = {
  title: { default: "Agent Registry", template: "%s · Agent Registry" },
  description: "Give your agent a credential websites can trust.",
};

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("");
}

export default async function RegistryLayout({ children }: { children: React.ReactNode }) {
  const to = await linker("registry");
  return (
    <div className="reg">
      <header className="reg-header">
        <div className="reg-header-inner">
          <a className="reg-brand" href={to("/")}>
            <RegistryMark />
            <span>Agent Registry</span>
          </a>
          <nav className="reg-nav" aria-label="Registry">
            <a href={to("/")}>Your agents</a>
            <span className="reg-operator">
              <span className="reg-avatar" aria-hidden="true">
                {initials(config.operatorName)}
              </span>
              <span>
                <span className="visually-hidden">Operator: </span>
                {config.operatorName}
              </span>
            </span>
          </nav>
        </div>
      </header>
      {children}
    </div>
  );
}
