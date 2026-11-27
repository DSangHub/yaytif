import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { LedgerEntryType, PayoutRail, PayoutStatus, Prisma } from '@prisma/client';
import { buildApp } from '../src/app.js';
import { createMockPrisma } from './helpers/mockPrisma.js';
import { createTestEnv } from './helpers/testEnv.js';

describe('Affiliate Postbacks, Wallet Ledger, and Atomic Payouts', () => {
  let app: FastifyInstance | undefined;

  afterEach(async () => {
    if (app) {
      await app.close();
      app = undefined;
    }
  });

  it('credits affiliate cashback idempotently and defaults referenceId to session.id when externalRef omitted', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({
      currency: 'USD',
      walletBalance: '5.000000',
    });
    const alert = mock.seedAlert({
      commissionShare: '0.5000',
    });
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const firstRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      payload: {
        clickId: session.clickId,
        commissionAmount: '12.345678',
        currency: 'usd',
        externalRef: 'aff-tx-1001',
      },
    });

    expect(firstRes.statusCode).toBe(200);
    // 12.345678 * 0.5000 = 6.172839
    expect(firstRes.json()).toMatchObject({
      status: 'credited',
      idempotent: false,
      sessionId: session.id,
      clickId: session.clickId,
      cashbackAmount: '6.172839',
      currency: 'USD',
      walletBalance: '11.172839',
    });
    expect(firstRes.json().ledgerEntryId).toBeTypeOf('string');

    const ledgerEntries = [...mock.state.ledgerEntries.values()];
    expect(ledgerEntries).toHaveLength(1);
    expect(ledgerEntries[0]?.type).toBe(LedgerEntryType.AFFILIATE_CASHBACK);
    expect(ledgerEntries[0]?.referenceId).toBe('aff-tx-1001');

    // Duplicate postback must be idempotent and NOT double-credit
    const duplicateRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      payload: {
        clickId: session.clickId,
        commissionAmount: '12.345678',
        currency: 'USD',
        externalRef: 'aff-tx-1001',
      },
    });

    expect(duplicateRes.statusCode).toBe(200);
    expect(duplicateRes.json()).toMatchObject({
      status: 'already_processed',
      idempotent: true,
      cashbackAmount: '0.000000',
      walletBalance: '11.172839',
      ledgerEntryId: null,
    });
    expect(mock.state.ledgerEntries.size).toBe(1);

    // Second session without externalRef -> referenceId falls back to session.id
    const session2 = mock.seedSession({ userId: user.id, alertId: alert.id });
    const fallbackRefRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      payload: {
        clickId: session2.clickId,
        commissionAmount: '4.000000',
        currency: 'USD',
      },
    });
    expect(fallbackRefRes.statusCode).toBe(200);
    const createdEntry = mock.state.ledgerEntries.get(fallbackRefRes.json().ledgerEntryId);
    expect(createdEntry?.referenceId).toBe(session2.id);
    expect(createdEntry?.amount.toFixed(6)).toBe('2.000000');
  });

  it('enforces POSTBACK_SECRET header when configured and handles concurrent duplicate postbacks', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({ currency: 'USD', walletBalance: '0.000000' });
    const alert = mock.seedAlert({ commissionShare: '0.5000' });
    const session = mock.seedSession({ userId: user.id, alertId: alert.id });

    app = await buildApp({
      env: createTestEnv({ POSTBACK_SECRET: 'super-secret-postback-token' }),
      prisma: mock.prisma,
    });

    const missingSecretRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      payload: {
        clickId: session.clickId,
        commissionAmount: '10.00',
        currency: 'USD',
      },
    });
    expect(missingSecretRes.statusCode).toBe(401);
    expect(missingSecretRes.json().error.code).toBe('INVALID_POSTBACK_SECRET');

    const wrongSecretRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      headers: { 'x-postback-secret': 'wrong-token' },
      payload: {
        clickId: session.clickId,
        commissionAmount: '10.00',
        currency: 'USD',
      },
    });
    expect(wrongSecretRes.statusCode).toBe(401);

    const okRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      headers: { 'x-postback-secret': 'super-secret-postback-token' },
      payload: {
        clickId: session.clickId,
        commissionAmount: '10.00',
        currency: 'USD',
      },
    });
    expect(okRes.statusCode).toBe(200);
    expect(okRes.json()).toMatchObject({
      status: 'credited',
      idempotent: false,
      cashbackAmount: '5.000000',
      walletBalance: '5.000000',
    });

    await app.close();

    // Test concurrent race where another tx marks converted=true between findUnique and updateMany
    const racingSession = mock.seedSession({ userId: user.id, alertId: alert.id });
    const racingPrisma = {
      ...mock.prisma,
      $transaction: async <T>(fn: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> => {
        return mock.prisma.$transaction(async (tx) => {
          const racingTx = {
            ...tx,
            adSession: {
              ...tx.adSession,
              updateMany: async (args: Prisma.AdSessionUpdateManyArgs) => {
                await tx.adSession.update({
                  where: { id: racingSession.id },
                  data: { converted: true },
                });
                return tx.adSession.updateMany(args);
              },
            },
          } as unknown as Prisma.TransactionClient;
          return fn(racingTx);
        });
      },
    } as unknown as typeof mock.prisma;

    app = await buildApp({
      env: createTestEnv({ POSTBACK_SECRET: 'super-secret-postback-token' }),
      prisma: racingPrisma,
    });

    const racedRes = await app.inject({
      method: 'POST',
      url: '/api/v1/postbacks/affiliate',
      headers: { 'x-postback-secret': 'super-secret-postback-token' },
      payload: {
        clickId: racingSession.clickId,
        commissionAmount: '10.00',
        currency: 'USD',
      },
    });
    expect(racedRes.statusCode).toBe(200);
    expect(racedRes.json()).toMatchObject({
      status: 'already_processed',
      idempotent: true,
      cashbackAmount: '0.000000',
      ledgerEntryId: null,
    });
    expect(mock.state.users.get(user.id)?.walletBalance.toFixed(6)).toBe('5.000000');
  });

  it('returns wallet summary and cursor-paginated ledger entries', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({
      currency: 'KES',
      walletBalance: '150.000000',
      dailyTimeEarned: '20.000000',
    });

    const e1 = mock.seedLedgerEntry({
      userId: user.id,
      amount: '10.000000',
      currency: 'KES',
      createdAt: new Date('2026-11-26T10:00:00.000Z'),
    });
    const e2 = mock.seedLedgerEntry({
      userId: user.id,
      amount: '20.000000',
      currency: 'KES',
      createdAt: new Date('2026-11-26T11:00:00.000Z'),
    });
    const e3 = mock.seedLedgerEntry({
      userId: user.id,
      amount: '30.000000',
      currency: 'KES',
      createdAt: new Date('2026-11-26T12:00:00.000Z'),
    });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const page1Res = await app.inject({
      method: 'GET',
      url: `/api/v1/wallet/${user.id}/ledger?limit=2`,
    });
    expect(page1Res.statusCode).toBe(200);
    const page1 = page1Res.json();
    expect(page1.wallet).toMatchObject({
      userId: user.id,
      walletBalance: '150.000000',
      dailyTimeEarned: '20.000000',
      currency: 'KES',
    });
    expect(page1.entries.map((e: { id: string }) => e.id)).toEqual([e3.id, e2.id]);
    expect(page1.pagination).toEqual({
      limit: 2,
      hasMore: true,
      nextCursor: e2.id,
    });

    const page2Res = await app.inject({
      method: 'GET',
      url: `/api/v1/wallet/${user.id}/ledger?limit=2&cursor=${page1.pagination.nextCursor}`,
    });
    expect(page2Res.statusCode).toBe(200);
    const page2 = page2Res.json();
    expect(page2.entries.map((e: { id: string }) => e.id)).toEqual([e1.id]);
    expect(page2.pagination).toEqual({
      limit: 2,
      hasMore: false,
      nextCursor: null,
    });
  });

  it('creates atomic payouts across regional rails, records negative ledger entries, and prevents overdraft/double-spend', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({
      countryCode: 'BR',
      currency: 'BRL',
      walletBalance: '100.000000',
    });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    // Valid withdrawal on PIX
    const payoutRes = await app.inject({
      method: 'POST',
      url: '/api/v1/payouts',
      payload: {
        userId: user.id,
        rail: PayoutRail.PIX,
        destination: 'cpf:12345678900',
        amount: '60.500000',
      },
    });

    expect(payoutRes.statusCode).toBe(201);
    const payoutBody = payoutRes.json();
    expect(payoutBody.payout).toMatchObject({
      userId: user.id,
      rail: 'PIX',
      destination: 'cpf:12345678900',
      amount: '60.500000',
      currency: 'BRL',
      status: 'PENDING',
    });
    expect(payoutBody.walletBalance).toBe('39.500000');

    const withdrawalLedger = mock.state.ledgerEntries.get(payoutBody.ledgerEntryId);
    expect(withdrawalLedger).toBeDefined();
    expect(withdrawalLedger?.type).toBe(LedgerEntryType.PAYOUT_WITHDRAWAL);
    expect(withdrawalLedger?.amount.toFixed(6)).toBe('-60.500000');
    expect(withdrawalLedger?.referenceId).toBe(payoutBody.payout.id);

    // Overdraft attempt (remaining balance is 39.500000)
    const overdraftRes = await app.inject({
      method: 'POST',
      url: '/api/v1/payouts',
      payload: {
        userId: user.id,
        rail: PayoutRail.PIX,
        destination: 'cpf:12345678900',
        amount: '40.000000',
      },
    });
    expect(overdraftRes.statusCode).toBe(409);
    expect(overdraftRes.json().error.code).toBe('INSUFFICIENT_BALANCE');
    expect(mock.state.users.get(user.id)?.walletBalance.toFixed(6)).toBe('39.500000');
    expect(mock.state.payoutRequests.size).toBe(1);
    expect(mock.state.ledgerEntries.size).toBe(1);

    // Verify all 6 supported rails are accepted when funded
    const remainingRails: PayoutRail[] = [
      PayoutRail.MERCADO_PAGO,
      PayoutRail.MPESA,
      PayoutRail.PAYSTACK_OPAY,
      PayoutRail.AIRTIME,
      PayoutRail.PAYPAL,
    ];
    for (const rail of remainingRails) {
      const railRes = await app.inject({
        method: 'POST',
        url: '/api/v1/payouts',
        payload: {
          userId: user.id,
          rail,
          destination: `dest-${rail.toLowerCase()}`,
          amount: '1.000000',
        },
      });
      expect(railRes.statusCode).toBe(201);
      expect(railRes.json().payout.rail).toBe(rail);
    }

    // Invalid rail and non-positive amount rejected strictly
    const badRailRes = await app.inject({
      method: 'POST',
      url: '/api/v1/payouts',
      payload: {
        userId: user.id,
        rail: 'CRYPTO_INVALID',
        destination: '0x123',
        amount: '1.00',
      },
    });
    expect(badRailRes.statusCode).toBe(400);

    const zeroAmountRes = await app.inject({
      method: 'POST',
      url: '/api/v1/payouts',
      payload: {
        userId: user.id,
        rail: PayoutRail.PAYPAL,
        destination: 'user@example.com',
        amount: '0.000000',
      },
    });
    expect(zeroAmountRes.statusCode).toBe(400);

    // List payouts for user
    const listRes = await app.inject({
      method: 'GET',
      url: `/api/v1/payouts?userId=${user.id}&status=${PayoutStatus.PENDING}`,
    });
    expect(listRes.statusCode).toBe(200);
    expect(listRes.json().count).toBe(6);

    const paidListRes = await app.inject({
      method: 'GET',
      url: `/api/v1/payouts?userId=${user.id}&status=${PayoutStatus.PAID}`,
    });
    expect(paidListRes.statusCode).toBe(200);
    expect(paidListRes.json().count).toBe(0);
  });

  it('prevents concurrent double-spend via conditional balance debit in transaction', async () => {
    const mock = createMockPrisma();
    const user = mock.seedUser({
      currency: 'NGN',
      walletBalance: '500.000000',
    });

    // Inject a custom prisma proxy where the conditional updateMany sees 0 rows
    // because a concurrent transaction already drained the balance between read and update.
    const racingPrisma = {
      ...mock.prisma,
      $transaction: async <T>(fn: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> => {
        return mock.prisma.$transaction(async (tx) => {
          const racingTx = {
            ...tx,
            user: {
              ...tx.user,
              updateMany: async (args: Prisma.UserUpdateManyArgs) => {
                // Simulate concurrent tx draining balance before conditional debit
                await tx.user.update({
                  where: { id: user.id },
                  data: { walletBalance: new Prisma.Decimal('100.000000') },
                });
                return tx.user.updateMany(args);
              },
            },
          } as unknown as Prisma.TransactionClient;
          return fn(racingTx);
        });
      },
    } as unknown as typeof mock.prisma;

    app = await buildApp({ env: createTestEnv(), prisma: racingPrisma });

    const res = await app.inject({
      method: 'POST',
      url: '/api/v1/payouts',
      payload: {
        userId: user.id,
        rail: PayoutRail.PAYSTACK_OPAY,
        destination: '08012345678',
        amount: '500.000000',
      },
    });

    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('INSUFFICIENT_BALANCE');
    // Transaction rolled back cleanly: no payout or ledger entry persisted
    expect(mock.state.payoutRequests.size).toBe(0);
    expect(mock.state.ledgerEntries.size).toBe(0);
    expect(mock.state.users.get(user.id)?.walletBalance.toFixed(6)).toBe('500.000000');
  });
});
