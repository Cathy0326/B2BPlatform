import { describe, expect, it } from 'vitest'
import type { AuditRecord } from '~/types/escrow'
import { canonicalJson, chainRecord, GENESIS, sha256Hex, verifyChain } from '~/utils/auditChain'

async function buildChain(n: number): Promise<AuditRecord[]> {
  const out: AuditRecord[] = []
  for (let i = 0; i < n; i++) {
    out.push(await chainRecord(out.at(-1), { eventType: 'DEAL_FUNDED', subject: `D-${i}`, actor: 'u-1', payload: { from: 'AWAITING_BALANCE', i }, createdAt: `2026-10-0${i + 1}T00:00:00.000Z` }))
  }
  return out
}

describe('audit hash chain', () => {
  it('uses the standard SHA-256', async () => {
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })

  it('sorts payload keys so the same data always hashes the same', () => {
    expect(canonicalJson({ b: 1, a: 2 })).toBe(canonicalJson({ a: 2, b: 1 }))
  })

  it('links each record to the previous one, starting from the genesis hash', async () => {
    const chain = await buildChain(3)
    expect(chain[0]!.prevHash).toBe(GENESIS)
    expect(chain[1]!.prevHash).toBe(chain[0]!.hash)
    expect(chain.map((r) => r.seq)).toEqual([1, 2, 3])
    expect(await verifyChain(chain)).toEqual({ valid: true, entries: 3, firstBrokenSeq: null, reason: null })
  })

  it('detects an edited record at the exact row', async () => {
    const chain = await buildChain(4)
    chain[1] = { ...chain[1]!, actor: 'attacker' }

    const v = await verifyChain(chain)
    expect(v.valid).toBe(false)
    expect(v.firstBrokenSeq).toBe(2)
    expect(v.reason).toMatch(/modified/)
  })

  it('detects a deleted record', async () => {
    const chain = await buildChain(4)
    chain.splice(2, 1)

    const v = await verifyChain(chain)
    expect(v.firstBrokenSeq).toBe(4)
    expect(v.reason).toMatch(/inserted or deleted/)
  })

  it("detects an edit even if the attacker recomputes that row's own hash", async () => {
    const chain = await buildChain(3)
    const forged = await chainRecord(chain[0], { eventType: 'DEAL_FUNDED', subject: 'D-1', actor: 'attacker', payload: { from: 'AWAITING_BALANCE', i: 1 }, createdAt: chain[1]!.createdAt })
    chain[1] = { ...forged, seq: 2 }

    expect((await verifyChain(chain)).firstBrokenSeq).toBe(3) // the NEXT record still points at the old hash
  })
})
