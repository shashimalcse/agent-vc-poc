"use server";

import { redirect } from "next/navigation";
import { isAccreditationLevel, isProvider } from "@/lib/agent";
import { config } from "@/lib/config";
import { createAgentOffer } from "@/lib/issuer2";
import { linker } from "@/lib/request";
import { mutate, randomId, readDb, type Agent } from "@/lib/store";

// The Registry has one operator (OPERATOR_NAME); there is no sign-in in the demo.

const field = (fd: FormData, name: string) => String(fd.get(name) ?? "").trim();

export async function registerAgentAction(formData: FormData) {
  const to = await linker("registry");
  const name = field(formData, "name");
  const provider = field(formData, "provider");
  const level = field(formData, "accreditationLevel");
  if (!name || name.length > 60 || !isProvider(provider) || !isAccreditationLevel(level)) {
    redirect(`${to("/")}?error=invalid#register-title`);
  }

  const agent = mutate((db) => {
    let agentId: string;
    do agentId = `agt_${randomId(6)}`;
    while (db.agents[agentId]);
    const agent: Agent = {
      agentId,
      name,
      provider,
      accreditationLevel: level,
      operatorName: config.operatorName,
      createdAt: new Date().toISOString(),
    };
    db.agents[agentId] = agent;
    return agent;
  });
  redirect(to(`/agents/${agent.agentId}`));
}

export async function issueCredentialAction(agentId: string) {
  const to = await linker("registry");
  const agent = readDb().agents[agentId];
  if (!agent) redirect(to("/"));

  let offer;
  try {
    offer = await createAgentOffer({
      agent_id: agent.agentId,
      agent_name: agent.name,
      provider: agent.provider,
      operator: agent.operatorName,
      accreditation_level: agent.accreditationLevel,
    });
  } catch (err) {
    console.error(err);
    redirect(`${to(`/agents/${agentId}`)}?error=issuer`);
  }
  mutate((db) => {
    db.offers[offer.offerId] = {
      offerId: offer.offerId,
      agentId,
      credentialOffer: offer.credentialOffer,
      expiresAt: offer.expiresAt,
      createdAt: new Date().toISOString(),
    };
  });
  // A new query string makes the router fetch the page again (a hash-only change would not).
  redirect(`${to(`/agents/${agentId}`)}?offer=${encodeURIComponent(offer.offerId)}`);
}
