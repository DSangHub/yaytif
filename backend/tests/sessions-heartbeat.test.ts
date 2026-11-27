import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { LedgerEntryType } from '@prisma/client';
import { buildApp } from '../src/app.js';
import { createMockPrisma } from './helpers/mockPrisma.js';
import { createTestEnv } from './helpers/testEnv.js';

describe('Ad Sessions and Attention-Time Heartbeats', () => {
  let app: FastifyInstance | undefined;

  afterEach(async () => {
    if (app) {
      await app.close();
      app = undefined;
    }
  });

  it('starts a session with a unique UUID clickId and substitutes every {click_id} macro', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({ countryCode: 'BR', currency: 'BRL' });
    const alert = mock.seedAlert({
      affiliateUrl:
        'https://track.yaytif.com/c/ifood-br?click_id={click_id}&subid={click_id}&market=BR',
      budgetRemaining: '100.000000',
      active: true,
    });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const res1 = await app.inject({
      method: 'POST',
      url: '/api/v1/sessions/start',
      payload: { userId: user.id, alertId: alert.id },
    });

    expect(res1.statusCode).toBe(201);
    const body1 = res1.json();
    expect(body1.sessionId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(body1.clickId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(body1.resolvedAffiliateUrl).toBe(
      `https://track.yaytif.com/c/ifood-br?click_id=${body1.clickId}&subid=${body1.clickId}&market=BR`,
    );
    expect(body1.resolvedAffiliateUrl).not.toContain('{click_id}');

    const res2 = await app.inject({
      method: 'POST',
      url: '/api/v1/sessions/start',
      payload: { userId: user.id, alertId: alert.id },
    });
    expect(res2.statusCode).toBe(201);
    expect(res2.json().clickId).not.toBe(body1.clickId);
  });

  it('rejects session start for inactive or exhausted alerts', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser();
    const inactiveAlert = mock.seedAlert({ active: false, budgetRemaining: '100.000000' });
    const emptyAlert = mock.seedAlert({ active: true, budgetRemaining: '0.000000' });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const inactiveRes = await app.inject({
      method: 'POST',
      url: '/api/v1/sessions/start',
      payload: { userId: user.id, alertId: inactiveAlert.id },
    });
    expect(inactiveRes.statusCode).toBe(409);
    expect(inactiveRes.json().error.code).toBe('ALERT_UNAVAILABLE');

    const emptyRes = await app.inject({
      method: 'POST',
      url: '/api/v1/sessions/start',
      payload: { userId: user.id, alertId: emptyAlert.id },
    });
    expect(emptyRes.statusCode).toBe(409);
    expect(emptyRes.json().error.code).toBe('ALERT_UNAVAILABLE');
  });

  it('calculates exact Prisma.Decimal heartbeat payout, updates ledger/wallet/budget atomically', async () => {
    const now = new Date('2026-11-26T15:00:00.000Z');
    const mock = createMockPrisma(() => now);
    const user = mock.seedUser({
      currency: 'USD',
      walletBalance: '1.000000',
      dailyTimeEarned: '0.000000',
      dailyCapResetAt: new Date('2026-11-27T15:00:00.000Z'),
    });
    const alert = mock.seedAlert({
      payoutPerMinute: '0.050000',
      budgetRemaining: '10.000000',
      active: true,
    });
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({
      env: createTestEnv({ DAILY_ATTENTION_EARNINGS_CAP: '5.000000' }),
      prisma: mock.prisma,
      now: () => now,
    });

    // 30s at $0.05/min = $0.025000 exact
    const hb1 = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 30 },
    });
    expect(hb1.statusCode).toBe(200);
    expect(hb1.json()).toMatchObject({
      sessionId: session.id,
      activeSeconds: 30,
      activeSecondsDelta: 30,
      creditedDelta: '0.025000',
      sessionCreditedTotal: '0.025000',
      walletBalance: '1.025000',
      dailyTimeEarned: '0.025000',
      dailyCap: '5.000000',
      dailyCapReached: false,
      alertBudgetRemaining: '9.975000',
      alertActive: true,
    });
    expect(hb1.json().ledgerEntryId).toBeTypeOf('string');

    // Repeating decimal check: 1s at $0.05/min = 0.000833333... -> rounded down to 0.000833
    const hb2 = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 1 },
    });
    expect(hb2.statusCode).toBe(200);
    expect(hb2.json()).toMatchObject({
      activeSeconds: 31,
      creditedDelta: '0.000833',
      sessionCreditedTotal: '0.025833',
      walletBalance: '1.025833',
      dailyTimeEarned: '0.025833',
      alertBudgetRemaining: '9.974167',
    });

    const entries = [...mock.state.ledgerEntries.values()];
    expect(entries).toHaveLength(2);
    expect(entries.every((e) => e.type === LedgerEntryType.ATTENTION_TIME)).toBe(true);
    expect(entries.every((e) => e.referenceId === session.id)).toBe(true);
  });

  it('enforces daily cap, avoids empty ledger entries at cap, and resets dailyTimeEarned (+24h UTC) when due', async () => {
    let currentTime = new Date('2026-11-26T18:00:00.000Z');
    const mock = createMockPrisma(() => currentTime);
    const user = mock.seedUser({
      currency: 'BRL',
      walletBalance: '10.000000',
      dailyTimeEarned: '0.950000',
      dailyCapResetAt: new Date('2026-11-26T20:00:00.000Z'),
    });
    const alert = mock.seedAlert({
      payoutPerMinute: '0.180000',
      budgetRemaining: '50.000000',
      active: true,
    });
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({
      env: createTestEnv({ DAILY_ATTENTION_EARNINGS_CAP: '1.000000' }),
      prisma: mock.prisma,
      now: () => currentTime,
    });

    // Raw payout for 60s = 0.180000, but only 0.050000 remains under daily cap 1.000000
    const cappedRes = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 60 },
    });
    expect(cappedRes.statusCode).toBe(200);
    expect(cappedRes.json()).toMatchObject({
      creditedDelta: '0.050000',
      walletBalance: '10.050000',
      dailyTimeEarned: '1.000000',
      dailyCapReached: true,
      alertBudgetRemaining: '49.950000',
    });
    expect(mock.state.ledgerEntries.size).toBe(1);

    // Another heartbeat before reset -> creditedDelta 0, NO new LedgerEntry
    const zeroRes = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 30 },
    });
    expect(zeroRes.statusCode).toBe(200);
    expect(zeroRes.json()).toMatchObject({
      activeSeconds: 90,
      creditedDelta: '0.000000',
      walletBalance: '10.050000',
      dailyTimeEarned: '1.000000',
      dailyCapReached: true,
      ledgerEntryId: null,
    });
    expect(mock.state.ledgerEntries.size).toBe(1);

    // Advance clock to exact dailyCapResetAt -> resets dailyTimeEarned to 0 and advances reset +24h UTC
    currentTime = new Date('2026-11-26T20:00:00.000Z');
    const resetRes = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 60 },
    });
    expect(resetRes.statusCode).toBe(200);
    expect(resetRes.json()).toMatchObject({
      activeSeconds: 150,
      creditedDelta: '0.180000',
      walletBalance: '10.230000',
      dailyTimeEarned: '0.180000',
      dailyCapResetAt: '2026-11-27T20:00:00.000Z',
      dailyCapReached: false,
      alertBudgetRemaining: '49.770000',
    });
    expect(resetRes.json().ledgerEntryId).toBeTypeOf('string');
    expect(mock.state.ledgerEntries.size).toBe(2);
  });

  it('caps payout at remaining alert budget and auto-deactivates campaign at zero budget', async () => {
    const now = new Date('2026-11-26T15:00:00.000Z');
    const mock = createMockPrisma(() => now);
    const user = mock.seedUser({
      currency: 'USD',
      walletBalance: '0.000000',
      dailyTimeEarned: '0.000000',
    });
    const alert = mock.seedAlert({
      payoutPerMinute: '0.600000',
      budgetRemaining: '0.120000',
      active: true,
    });
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({
      env: createTestEnv({ DAILY_ATTENTION_EARNINGS_CAP: '10.000000' }),
      prisma: mock.prisma,
      now: () => now,
    });

    // 30s at 0.60/min = 0.30, clamped to remaining budget 0.120000 -> auto-deactivates alert
    const res = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 30 },
    });

    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({
      creditedDelta: '0.120000',
      walletBalance: '0.120000',
      alertBudgetRemaining: '0.000000',
      alertActive: false,
    });

    const storedAlert = mock.state.sponsoredAlerts.get(alert.id);
    expect(storedAlert?.active).toBe(false);
    expect(storedAlert?.budgetRemaining.toFixed(6)).toBe('0.000000');

    // Subsequent heartbeat on depleted campaign credits 0 and creates no additional ledger entry
    const postDepletionRes = await app.inject({
      method: 'POST',
      url: `/api/v1/sessions/${session.id}/heartbeat`,
      payload: { activeSecondsDelta: 15 },
    });
    expect(postDepletionRes.statusCode).toBe(200);
    expect(postDepletionRes.json()).toMatchObject({
      creditedDelta: '0.000000',
      alertActive: false,
      ledgerEntryId: null,
    });
    expect(mock.state.ledgerEntries.size).toBe(1);
  });

  it('rejects out-of-bounds or invalid heartbeat payloads strictly', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser();
    const alert = mock.seedAlert();
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    for (const payload of [
      { activeSecondsDelta: 0 },
      { activeSecondsDelta: -5 },
      { activeSecondsDelta: 61 },
      { activeSecondsDelta: 10.5 },
      { activeSecondsDelta: 10, notificationTitle: 'disallowed' },
    ]) {
      const res = await app.inject({
        method: 'POST',
        url: `/api/v1/sessions/${session.id}/heartbeat`,
        payload,
      });
      expect(res.statusCode).toBe(400);
      expect(res.json().error.code).toBe('VALIDATION_ERROR');
    }
  });
});
