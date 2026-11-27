import { randomUUID } from 'node:crypto';
import { LedgerEntryType, Prisma } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import {
  DECIMAL_ZERO,
  calculateAttentionPayout,
  formatDecimal6,
  roundMoney,
  toDecimal,
} from '../lib/decimal.js';
import { conflict, notFound } from '../lib/errors.js';
import { emptyQuerySchema, parseInput, uuidSchema } from '../lib/validation.js';
import type { RouteContext } from './context.js';

const DAY_MS = 24 * 60 * 60 * 1000;

const startSessionBodySchema = z
  .object({
    userId: uuidSchema,
    alertId: uuidSchema,
  })
  .strict();

const sessionIdParamsSchema = z
  .object({
    sessionId: uuidSchema,
  })
  .strict();

const heartbeatBodySchema = z
  .object({
    activeSecondsDelta: z.number().int().min(1).max(60),
  })
  .strict();

export function resolveAffiliateUrl(templateUrl: string, clickId: string): string {
  return templateUrl.replaceAll('{click_id}', encodeURIComponent(clickId));
}

export function registerSessionRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.post('/sessions/start', async (request, reply) => {
    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(startSessionBodySchema, request.body);

    const [user, alert] = await Promise.all([
      ctx.prisma.user.findUnique({
        where: { id: body.userId },
        select: { id: true },
      }),
      ctx.prisma.sponsoredAlert.findUnique({
        where: { id: body.alertId },
      }),
    ]);

    if (!user) {
      throw notFound('User not found', 'USER_NOT_FOUND');
    }

    if (!alert) {
      throw notFound('Sponsored alert not found', 'ALERT_NOT_FOUND');
    }

    if (!alert.active || toDecimal(alert.budgetRemaining).lte(0)) {
      throw conflict(
        'Sponsored alert is inactive or has no remaining budget',
        'ALERT_UNAVAILABLE',
      );
    }

    const clickId = randomUUID();

    const session = await ctx.prisma.adSession.create({
      data: {
        userId: user.id,
        alertId: alert.id,
        clickId,
      },
    });

    return reply.code(201).send({
      sessionId: session.id,
      clickId: session.clickId,
      resolvedAffiliateUrl: resolveAffiliateUrl(alert.affiliateUrl, session.clickId),
    });
  });

  app.post('/sessions/:sessionId/heartbeat', async (request, reply) => {
    const params = parseInput(sessionIdParamsSchema, request.params);
    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(heartbeatBodySchema, request.body);

    const dailyCap = toDecimal(ctx.env.DAILY_ATTENTION_EARNINGS_CAP);
    const now = ctx.now();

    const result = await ctx.prisma.$transaction(async (tx) => {
      const session = await tx.adSession.findUnique({
        where: { id: params.sessionId },
        include: {
          user: true,
          alert: true,
        },
      });

      if (!session) {
        throw notFound('Ad session not found', 'SESSION_NOT_FOUND');
      }

      const needsDailyReset = now.getTime() >= session.user.dailyCapResetAt.getTime();
      const effectiveDailyEarned = needsDailyReset
        ? DECIMAL_ZERO
        : toDecimal(session.user.dailyTimeEarned);
      const nextDailyCapResetAt = needsDailyReset
        ? new Date(now.getTime() + DAY_MS)
        : session.user.dailyCapResetAt;

      const remainingDailyCap = Prisma.Decimal.max(
        DECIMAL_ZERO,
        dailyCap.sub(effectiveDailyEarned),
      );

      const currentAlertBudget = session.alert.active
        ? Prisma.Decimal.max(DECIMAL_ZERO, toDecimal(session.alert.budgetRemaining))
        : DECIMAL_ZERO;

      const creditedDelta = calculateAttentionPayout(
        session.alert.payoutPerMinute,
        body.activeSecondsDelta,
        remainingDailyCap,
        currentAlertBudget,
      );

      let finalBudgetRemaining = currentAlertBudget;
      let alertActive = session.alert.active;

      if (creditedDelta.gt(0)) {
        const alertUpdate = await tx.sponsoredAlert.updateMany({
          where: {
            id: session.alert.id,
            active: true,
            budgetRemaining: { gte: creditedDelta },
          },
          data: {
            budgetRemaining: { decrement: creditedDelta },
          },
        });

        if (alertUpdate.count === 0) {
          throw conflict(
            'Sponsored alert budget changed concurrently; retry heartbeat',
            'ALERT_BUDGET_CONFLICT',
          );
        }

        const refreshedAlert = await tx.sponsoredAlert.findUnique({
          where: { id: session.alert.id },
        });

        finalBudgetRemaining = refreshedAlert
          ? toDecimal(refreshedAlert.budgetRemaining)
          : roundMoney(currentAlertBudget.sub(creditedDelta));
        alertActive = refreshedAlert ? refreshedAlert.active : session.alert.active;

        if (finalBudgetRemaining.lte(0)) {
          await tx.sponsoredAlert.update({
            where: { id: session.alert.id },
            data: {
              budgetRemaining: DECIMAL_ZERO,
              active: false,
            },
          });
          finalBudgetRemaining = DECIMAL_ZERO;
          alertActive = false;
        }
      }

      const updatedSession = await tx.adSession.update({
        where: { id: session.id },
        data: {
          activeSeconds: { increment: body.activeSecondsDelta },
          ...(creditedDelta.gt(0) ? { creditedAmount: { increment: creditedDelta } } : {}),
        },
      });

      let updatedUser = session.user;
      if (needsDailyReset) {
        const userResetUpdate = await tx.user.updateMany({
          where: {
            id: session.user.id,
            dailyCapResetAt: { lte: now },
          },
          data: {
            dailyTimeEarned: creditedDelta,
            dailyCapResetAt: nextDailyCapResetAt,
            ...(creditedDelta.gt(0) ? { walletBalance: { increment: creditedDelta } } : {}),
          },
        });

        if (userResetUpdate.count === 0) {
          throw conflict(
            'Daily cap reset state changed concurrently; retry heartbeat',
            'DAILY_CAP_CONFLICT',
          );
        }

        const refreshedUser = await tx.user.findUnique({
          where: { id: session.user.id },
        });
        if (!refreshedUser) {
          throw notFound('User not found', 'USER_NOT_FOUND');
        }
        updatedUser = refreshedUser;
      } else if (creditedDelta.gt(0)) {
        const maxPriorDailyEarned = roundMoney(dailyCap.sub(creditedDelta));
        const userCreditUpdate = await tx.user.updateMany({
          where: {
            id: session.user.id,
            dailyTimeEarned: { lte: maxPriorDailyEarned },
          },
          data: {
            walletBalance: { increment: creditedDelta },
            dailyTimeEarned: { increment: creditedDelta },
          },
        });

        if (userCreditUpdate.count === 0) {
          throw conflict(
            'Daily attention earnings cap changed concurrently; retry heartbeat',
            'DAILY_CAP_CONFLICT',
          );
        }

        const refreshedUser = await tx.user.findUnique({
          where: { id: session.user.id },
        });
        if (!refreshedUser) {
          throw notFound('User not found', 'USER_NOT_FOUND');
        }
        updatedUser = refreshedUser;
      }

      let ledgerEntryId: string | null = null;
      if (creditedDelta.gt(0)) {
        const ledgerEntry = await tx.ledgerEntry.create({
          data: {
            userId: session.user.id,
            type: LedgerEntryType.ATTENTION_TIME,
            amount: creditedDelta,
            currency: session.user.currency,
            referenceId: session.id,
          },
        });
        ledgerEntryId = ledgerEntry.id;
      }

      const finalDailyTimeEarned = toDecimal(updatedUser.dailyTimeEarned);

      return {
        sessionId: updatedSession.id,
        activeSeconds: updatedSession.activeSeconds,
        activeSecondsDelta: body.activeSecondsDelta,
        creditedDelta: formatDecimal6(creditedDelta),
        sessionCreditedTotal: formatDecimal6(updatedSession.creditedAmount),
        walletBalance: formatDecimal6(updatedUser.walletBalance),
        dailyTimeEarned: formatDecimal6(finalDailyTimeEarned),
        dailyCap: formatDecimal6(dailyCap),
        dailyCapResetAt: updatedUser.dailyCapResetAt.toISOString(),
        dailyCapReached: finalDailyTimeEarned.gte(dailyCap),
        alertBudgetRemaining: formatDecimal6(finalBudgetRemaining),
        alertActive,
        ledgerEntryId,
      };
    });

    return reply.code(200).send(result);
  });
}
