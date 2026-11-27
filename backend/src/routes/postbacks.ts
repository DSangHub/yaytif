import { createHash, timingSafeEqual } from 'node:crypto';
import { LedgerEntryType } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { calculateAffiliateCashback, formatDecimal6 } from '../lib/decimal.js';
import { notFound, unauthorized } from '../lib/errors.js';
import {
  currencySchema,
  emptyQuerySchema,
  parseInput,
  positiveDecimalStringSchema,
  uuidSchema,
} from '../lib/validation.js';
import type { RouteContext } from './context.js';

const affiliatePostbackBodySchema = z
  .object({
    clickId: uuidSchema,
    commissionAmount: positiveDecimalStringSchema,
    currency: currencySchema,
    externalRef: z.string().trim().min(1).max(128).optional(),
  })
  .strict();

function verifyPostbackSecret(expectedSecret: string, providedHeader: unknown): boolean {
  if (typeof providedHeader !== 'string' || providedHeader.length === 0) {
    return false;
  }
  const expectedDigest = createHash('sha256').update(expectedSecret).digest();
  const providedDigest = createHash('sha256').update(providedHeader).digest();
  return timingSafeEqual(expectedDigest, providedDigest);
}

export function registerPostbackRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.post('/postbacks/affiliate', async (request, reply) => {
    if (ctx.env.POSTBACK_SECRET !== undefined) {
      const providedSecret = request.headers['x-postback-secret'];
      if (!verifyPostbackSecret(ctx.env.POSTBACK_SECRET, providedSecret)) {
        throw unauthorized('Invalid or missing postback secret', 'INVALID_POSTBACK_SECRET');
      }
    }

    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(affiliatePostbackBodySchema, request.body);

    const result = await ctx.prisma.$transaction(async (tx) => {
      const session = await tx.adSession.findUnique({
        where: { clickId: body.clickId },
        include: {
          alert: true,
          user: true,
        },
      });

      if (!session) {
        throw notFound('Ad session not found for clickId', 'SESSION_NOT_FOUND');
      }

      if (session.converted) {
        return {
          status: 'already_processed' as const,
          idempotent: true,
          sessionId: session.id,
          clickId: session.clickId,
          cashbackAmount: formatDecimal6(0),
          currency: body.currency,
          walletBalance: formatDecimal6(session.user.walletBalance),
          ledgerEntryId: null as string | null,
        };
      }

      const claimResult = await tx.adSession.updateMany({
        where: {
          id: session.id,
          converted: false,
        },
        data: {
          converted: true,
        },
      });

      if (claimResult.count === 0) {
        const latestUser = await tx.user.findUnique({
          where: { id: session.userId },
        });
        return {
          status: 'already_processed' as const,
          idempotent: true,
          sessionId: session.id,
          clickId: session.clickId,
          cashbackAmount: formatDecimal6(0),
          currency: body.currency,
          walletBalance: formatDecimal6(latestUser?.walletBalance ?? session.user.walletBalance),
          ledgerEntryId: null as string | null,
        };
      }

      const cashbackAmount = calculateAffiliateCashback(
        body.commissionAmount,
        session.alert.commissionShare,
      );

      let updatedUser = session.user;
      let ledgerEntryId: string | null = null;

      if (cashbackAmount.gt(0)) {
        updatedUser = await tx.user.update({
          where: { id: session.userId },
          data: {
            walletBalance: { increment: cashbackAmount },
          },
        });

        const ledgerEntry = await tx.ledgerEntry.create({
          data: {
            userId: session.userId,
            type: LedgerEntryType.AFFILIATE_CASHBACK,
            amount: cashbackAmount,
            currency: body.currency,
            referenceId: body.externalRef ?? session.id,
          },
        });

        ledgerEntryId = ledgerEntry.id;
      }

      return {
        status: 'credited' as const,
        idempotent: false,
        sessionId: session.id,
        clickId: session.clickId,
        cashbackAmount: formatDecimal6(cashbackAmount),
        currency: body.currency,
        walletBalance: formatDecimal6(updatedUser.walletBalance),
        ledgerEntryId,
      };
    });

    return reply.code(200).send(result);
  });
}
