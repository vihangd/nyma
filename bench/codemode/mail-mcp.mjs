// A stub mail MCP server shaped like eM Client's: search returns ids, get_email_data
// returns long HTML bodies (signatures, quoted threads, tracking markup), and
// get_attachment returns a base64 `resource` blob. Deterministic, so a run is
// comparable to the next.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const people = ["Renga VR <rengavr@indygen.ai>", "Jyoti S <jyoti@fino.in>", "Amir K <amir@prakat.in>", "Newsletter <news@vendor.com>"];
const topics = ["AFU module BRD review", "FRM rule tuning", "AML STR generation", "Contact centre BRD", "Lunch on Friday", "Invoice March"];
const asks = ["please review and sign off by Monday", "share the updated thresholds", "confirm the STR format for FIU", "comment on the IVR flow", "RSVP", "clear the outstanding payment"];

// MAIL_SCALE=n: n× the mails and n× the quoted thread per body. 6 ≈ the eM
// Client session (≈400 KB of bodies for the matching mails).
const scale = Number(process.env.MAIL_SCALE || 1);
const mails = [];
for (let i = 0; i < 18 * scale; i++) {
  const from = people[i % people.length], t = i % topics.length;
  const day = 8 + (i % 9);
  const att = (t <= 2 && i % 2 === 0) ? [{ attachment_id: `att-${i}`, name: `${topics[t].replace(/\s+/g, "_")}_v${i}.pdf`, size: 2048 }] : [];
  const quoted = Array.from({ length: 30 * scale }, (_, k) => `<div class="gmail_quote" style="margin:0 0 0 .8ex;border-left:1px #ccc solid;padding-left:1ex"><p style="font-family:Calibri,sans-serif;font-size:11pt">&gt; Earlier message ${k} in the thread about ${topics[(t + k) % topics.length]}. Regards, team.</p></div>`).join("\n");
  mails.push({
    email_id: `mail-${i}`, from, subject: `${i % 3 === 0 ? "Re: " : ""}${topics[t]}`,
    received: `2026-10-${String(day).padStart(2, "0")}T09:${String(i).padStart(2, "0")}:00Z`,
    attachments: att,
    html: `<html><head><style>p{margin:0}</style></head><body><p>Hi Vihang,</p><p>Regarding ${topics[t]}: ${asks[t]}.</p><p>Thanks,<br>${from.split(" <")[0]}</p><table width="600"><tr><td><img src="https://track.example/${i}.gif" width="1"></td></tr></table>\n${quoted}</body></html>`,
  });
}

const server = new McpServer({ name: "mail", version: "1.0.0" });
server.registerTool("search_emails", {
  description: "Search emails. Returns id, from, subject, received date and attachment names — not bodies.",
  inputSchema: { from_contains: z.string().optional().describe("Substring of the sender"), subject_contains: z.string().optional().describe("Substring of the subject"), date_from: z.string().optional().describe("ISO date, inclusive"), limit: z.number().optional() },
  annotations: { readOnlyHint: true },
}, async (a) => {
  const r = mails.filter(m => (!a.from_contains || m.from.toLowerCase().includes(a.from_contains.toLowerCase()))
    && (!a.subject_contains || m.subject.toLowerCase().includes(a.subject_contains.toLowerCase()))
    && (!a.date_from || m.received >= a.date_from)).slice(0, a.limit ?? 50)
    .map(({ html, ...m }) => m);
  return { content: [{ type: "text", text: JSON.stringify(r, null, 2) }] };
});
server.registerTool("get_email_data", {
  description: "Full email data including the HTML body for each id.",
  inputSchema: { email_ids: z.array(z.string()) },
  annotations: { readOnlyHint: true },
}, async (a) => ({ content: [{ type: "text", text: JSON.stringify(mails.filter(m => a.email_ids.includes(m.email_id)), null, 2) }] }));
server.registerTool("get_attachment", {
  description: "Attachment content as an embedded resource.",
  inputSchema: { attachment_id: z.string() },
  annotations: { readOnlyHint: true },
}, async (a) => {
  const m = mails.find(m => m.attachments.some(x => x.attachment_id === a.attachment_id));
  if (!m) return { isError: true, content: [{ type: "text", text: "no such attachment" }] };
  const x = m.attachments.find(x => x.attachment_id === a.attachment_id);
  const blob = Buffer.from(`%PDF-1.4 stub ${x.name}\n` + "x".repeat(x.size)).toString("base64");
  return { content: [{ type: "resource", resource: { uri: `mail://attachments/${x.name}`, mimeType: "application/pdf", blob } }] };
});
await server.connect(new StdioServerTransport());
