import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { parseEnv } from '../src/config/env.js';
import { createMockPrisma } from './helpers/mockPrisma.js';
import { createTestEnv } from './helpers/testEnv.js';

describe('Users, Alerts, Privacy Validation, and Health Endpoints', () => {
  let app: FastifyInstance | undefined;

  afterEach(async () => {
    if (app) {
      await app.close();
      app = undefined;
    }
  });

  it('registers a user and upserts idempotently by phoneHash without clobbering walletBalance', async () => {
    const fixedNow = new Date('2026-11-26T12:00:00.000Z');
    const mock = createMockPrisma(() => fixedNow);
    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma, now: () => fixedNow });

    const registerRes = await app.inject({
      method: 'POST',
      url: '/api/v1/users/register',
      payload: {
        phoneHash: 'sha256-br-user-999990001',
        countryCode: 'br',
        locale: 'pt-BR',
        currency: 'brl',
        interests: ['food_delivery', 'ecommerce', 'food_delivery'],
      },
    });

    expect(registerRes.statusCode).toBe(200);
    const created = registerRes.json();
    expect(created.id).toBeTypeOf('string');
    expect(created.countryCode).toBe('BR');
    expect(created.locale).toBe('pt-BR');
    expect(created.currency).toBe('BRL');
    expect(created.interests).toEqual(['food_delivery', 'ecommerce']);
    expect(created.walletBalance).toBe('0.000000');
    expect(created.dailyTimeEarned).toBe('0.000000');
    expect(created.dailyCapResetAt).toBe('2026-11-27T12:00:00.000Z');
    expect(created).not.toHaveProperty('phoneHash');

    // Simulate earned balance before re-registering same phoneHash
    const storedUser = mock.state.users.get(created.id);
    expect(storedUser).toBeDefined();
    storedUser!.walletBalance = storedUser!.walletBalance.add('15.250000');

    const upsertRes = await app.inject({
      method: 'POST',
      url: '/api/v1/users/register',
      payload: {
        phoneHash: 'sha256-br-user-999990001',
        countryCode: 'BR',
        locale: 'pt-BR',
        currency: 'BRL',
        interests: ['fintech'],
      },
    });

    expect(upsertRes.statusCode).toBe(200);
    const updated = upsertRes.json();
    expect(updated.id).toBe(created.id);
    expect(updated.interests).toEqual(['fintech']);
    expect(updated.walletBalance).toBe('15.250000');
  });

  it('rejects unknown/disallowed fields including private notification metadata (.strict())', async () => {
    const mock = createMockPrisma();
    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const privacyViolationRes = await app.inject({
      method: 'POST',
      url: '/api/v1/users/register',
      payload: {
        phoneHash: 'sha256-ke-user-0001',
        countryCode: 'KE',
        locale: 'sw-KE',
        currency: 'KES',
        interests: ['telecom'],
        notificationTitle: 'Private WhatsApp Message',
        notificationBody: 'Secret text',
        senderName: 'Alice',
      },
    });

    expect(privacyViolationRes.statusCode).toBe(400);
    const body = privacyViolationRes.json();
    expect(body.error.code).toBe('VALIDATION_ERROR');
    expect(mock.state.users.size).toBe(0);

    const balanceInjectionRes = await app.inject({
      method: 'POST',
      url: '/api/v1/users/register',
      payload: {
        phoneHash: 'sha256-ke-user-0002',
        countryCode: 'KE',
        locale: 'sw-KE',
        currency: 'KES',
        interests: ['telecom'],
        walletBalance: '999999.000000',
      },
    });

    expect(balanceInjectionRes.statusCode).toBe(400);
    expect(balanceInjectionRes.json().error.code).toBe('VALIDATION_ERROR');
  });

  it('fetches user profile via GET /api/v1/users/:userId and updates via PATCH /interests', async () => {
    const mock = createMockPrisma();
    const seeded = mock.seedUser({
      countryCode: 'NG',
      locale: 'en-NG',
      currency: 'NGN',
      interests: ['fintech'],
      walletBalance: '4250.500000',
      dailyTimeEarned: '630.000000',
    });
    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const getRes = await app.inject({
      method: 'GET',
      url: `/api/v1/users/${seeded.id}`,
    });

    expect(getRes.statusCode).toBe(200);
    expect(getRes.json()).toMatchObject({
      id: seeded.id,
      countryCode: 'NG',
      locale: 'en-NG',
      currency: 'NGN',
      interests: ['fintech'],
      walletBalance: '4250.500000',
      dailyTimeEarned: '630.000000',
    });

    const patchRes = await app.inject({
      method: 'PATCH',
      url: `/api/v1/users/${seeded.id}/interests`,
      payload: {
        interests: ['telecom', 'ecommerce'],
        locale: 'ar-EG',
        countryCode: 'eg',
        currency: 'egp',
      },
    });

    expect(patchRes.statusCode).toBe(200);
    expect(patchRes.json()).toMatchObject({
      id: seeded.id,
      countryCode: 'EG',
      locale: 'ar-EG',
      currency: 'EGP',
      interests: ['telecom', 'ecommerce'],
      walletBalance: '4250.500000',
    });

    const invalidUuidRes = await app.inject({
      method: 'GET',
      url: '/api/v1/users/not-a-uuid',
    });
    expect(invalidUuidRes.statusCode).toBe(400);

    const notFoundRes = await app.inject({
      method: 'GET',
      url: '/api/v1/users/00000000-0000-4000-8000-000000000099',
    });
    expect(notFoundRes.statusCode).toBe(404);
    expect(notFoundRes.json().error.code).toBe('USER_NOT_FOUND');
  });

  it('filters active funded alerts by countryCode, category, and userId interest priority', async () => {
    const mock = createMockPrisma();
    const brUser = mock.seedUser({
      countryCode: 'BR',
      locale: 'pt-BR',
      currency: 'BRL',
      interests: ['food_delivery'],
    });

    const lowPayoutFood = mock.seedAlert({
      advertiserName: 'iFood Brasil',
      category: 'food_delivery',
      countryCodes: ['BR'],
      payoutPerMinute: '0.180000',
      budgetRemaining: '100.000000',
      active: true,
    });
    const highPayoutEcom = mock.seedAlert({
      advertiserName: 'Mercado Livre',
      category: 'ecommerce',
      countryCodes: ['BR'],
      payoutPerMinute: '0.350000',
      budgetRemaining: '500.000000',
      active: true,
    });
    // Excluded: inactive
    mock.seedAlert({
      advertiserName: 'Paused Brand',
      category: 'food_delivery',
      countryCodes: ['BR'],
      payoutPerMinute: '0.990000',
      budgetRemaining: '100.000000',
      active: false,
    });
    // Excluded: zero budget
    mock.seedAlert({
      advertiserName: 'Empty Budget Brand',
      category: 'food_delivery',
      countryCodes: ['BR'],
      payoutPerMinute: '0.990000',
      budgetRemaining: '0.000000',
      active: true,
    });
    // Excluded: different country
    mock.seedAlert({
      advertiserName: 'Jumia Kenya',
      category: 'ecommerce',
      countryCodes: ['KE'],
      payoutPerMinute: '4.500000',
      budgetRemaining: '500.000000',
      active: true,
    });

    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    // Without userId: sorted by payoutPerMinute DESC
    const countryRes = await app.inject({
      method: 'GET',
      url: '/api/v1/alerts?countryCode=br',
    });
    expect(countryRes.statusCode).toBe(200);
    const countryBody = countryRes.json();
    expect(countryBody.countryCode).toBe('BR');
    expect(countryBody.count).toBe(2);
    expect(countryBody.alerts.map((a: { id: string }) => a.id)).toEqual([
      highPayoutEcom.id,
      lowPayoutFood.id,
    ]);

    // With userId: prioritizes matching interest category (food_delivery) first
    const personalizedRes = await app.inject({
      method: 'GET',
      url: `/api/v1/alerts?userId=${brUser.id}`,
    });
    expect(personalizedRes.statusCode).toBe(200);
    const personalizedBody = personalizedRes.json();
    expect(personalizedBody.countryCode).toBe('BR');
    expect(personalizedBody.alerts.map((a: { id: string }) => a.id)).toEqual([
      lowPayoutFood.id,
      highPayoutEcom.id,
    ]);

    // With explicit category filter
    const categoryRes = await app.inject({
      method: 'GET',
      url: '/api/v1/alerts?countryCode=BR&category=ecommerce',
    });
    expect(categoryRes.statusCode).toBe(200);
    expect(categoryRes.json().alerts.map((a: { id: string }) => a.id)).toEqual([highPayoutEcom.id]);

    // Missing both countryCode and userId -> 400
    const missingFilterRes = await app.inject({
      method: 'GET',
      url: '/api/v1/alerts',
    });
    expect(missingFilterRes.statusCode).toBe(400);

    // Unknown query param rejected by .strict()
    const extraQueryRes = await app.inject({
      method: 'GET',
      url: '/api/v1/alerts?countryCode=BR&notificationBody=leak',
    });
    expect(extraQueryRes.statusCode).toBe(400);
  });

  it('serves /health and /health/ready and validates environment configuration', async () => {
    const mock = createMockPrisma();
    app = await buildApp({ env: createTestEnv(), prisma: mock.prisma });

    const healthRes = await app.inject({ method: 'GET', url: '/health' });
    expect(healthRes.statusCode).toBe(200);
    expect(healthRes.json().status).toBe('ok');

    const readyRes = await app.inject({ method: 'GET', url: '/health/ready' });
    expect(readyRes.statusCode).toBe(200);
    expect(readyRes.json().database).toBe('connected');

    mock.setDatabaseHealthy(false);
    const degradedRes = await app.inject({ method: 'GET', url: '/health/ready' });
    expect(degradedRes.statusCode).toBe(503);
    expect(degradedRes.json().database).toBe('disconnected');

    const parsed = parseEnv({
      DATABASE_URL: 'postgresql://localhost:5432/yaytif',
      DAILY_ATTENTION_EARNINGS_CAP: '25.500000',
      POSTBACK_SECRET: '',
    });
    expect(parsed.DAILY_ATTENTION_EARNINGS_CAP).toBe('25.500000');
    expect(parsed.POSTBACK_SECRET).toBeUndefined();

    expect(() =>
      parseEnv({
        DATABASE_URL: 'postgresql://localhost:5432/yaytif',
        DAILY_ATTENTION_EARNINGS_CAP: '-5',
      }),
    ).toThrow(/DAILY_ATTENTION_EARNINGS_CAP/);
  });
});
