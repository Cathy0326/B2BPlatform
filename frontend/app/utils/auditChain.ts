import type { AuditRecord, AuditVerification } from '~/types/escrow'

/**
 * Tamper-evident audit log, same scheme as the backend's JdbcAuditTrail:
 *   hash = SHA-256( JSON array [prevHash, eventType, subject, actor, createdAt, canonicalPayload] )
 * A JSON array is unambiguous ("a|b"+"c" vs "a"+"b|c" cannot collide), and sorted payload keys make
 * the payload canonical. Editing or deleting any record breaks every hash after it.
 */

export const GENESIS = '0'.repeat(64)

export function canonicalJson(payload: Record<string, unknown>): string {
  return JSON.stringify(Object.fromEntries(Object.entries(payload).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))))
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function hashOf(prevHash: string, eventType: string, subject: string, actor: string, createdAt: string, payload: string) {
  return sha256Hex(JSON.stringify([prevHash, eventType, subject, actor, createdAt, payload]))
}

export async function chainRecord(
  previous: AuditRecord | undefined,
  event: { eventType: string; subject: string; actor: string; payload: Record<string, unknown>; createdAt: string },
): Promise<AuditRecord> {
  const prevHash = previous?.hash ?? GENESIS
  const payload = canonicalJson(event.payload)
  return {
    seq: (previous?.seq ?? 0) + 1,
    eventType: event.eventType,
    subject: event.subject,
    actor: event.actor,
    payload,
    createdAt: event.createdAt,
    prevHash,
    hash: await hashOf(prevHash, event.eventType, event.subject, event.actor, event.createdAt, payload),
  }
}

/** Walk the chain from the start and recompute every hash. Reports the FIRST broken record. */
export async function verifyChain(records: AuditRecord[]): Promise<AuditVerification> {
  let expectedPrev = GENESIS
  for (const r of records) {
    if (r.prevHash !== expectedPrev) {
      return { valid: false, entries: records.length, firstBrokenSeq: r.seq, reason: 'prev_hash does not match the previous record (row inserted or deleted)' }
    }
    if ((await hashOf(r.prevHash, r.eventType, r.subject, r.actor, r.createdAt, r.payload)) !== r.hash) {
      return { valid: false, entries: records.length, firstBrokenSeq: r.seq, reason: 'content does not match its hash (row modified)' }
    }
    expectedPrev = r.hash
  }
  return { valid: true, entries: records.length, firstBrokenSeq: null, reason: null }
}
