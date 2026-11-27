import { LedgerEntryType, PayoutRail, PayoutStatus } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { formatDecimal6, toDecimal } from '../lib/decimal.js';
import { conflict, notFound } from '../lib/errors.js';
import { serializePayoutRequest } from '../lib/serializers.js';
import {
  emptyQuerySchema,
  parseInput,
  positiveDecimalStringSchema,
  uuidSchema,
} from '../lib/validation.js';
import type { RouteContext } from './context.js';

const createPayoutBodySchema = z
  .object({
    userId: uuidSchema,
    rail: z.nativeEnum(PayoutRail),
    destination: z.string().trim().min(3).max(255),
    amount: positiveDecimalStringSchema,
  })
  .strict();

const listPayoutsQuerySchema = z
  .object({
    userId: uuidSchema,
    status: z.nativeEnum(PayoutStatus).optional(),
    limit: z.coerce.number().int().min(1).max(100).optional().default(50),
  })
  .strict();

export function registerPayoutRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.post('/payouts', async (request, reply) => {
    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(createPayoutBodySchema, request.body);

    const withdrawalAmount = toDecimal(body.amount);

    const result = await ctx.prisma.$transaction(async (tx) => {
      const user = await tx.user.findUnique({
        where: { id: body.userId },
      });

      if (!user) {
        throw notFound('User not found', 'USER_NOT_FOUND');
      }

      const debitResult = await tx.user.updateMany({
        where: {
          id: user.id,
          walletBalance: { gte: withdrawalAmount },
        },
        data: {
          walletBalance: { decrement: withdrawalAmount },
        },
      });

      if (debitResult.count === 0) {
        throw conflict('Insufficient wallet balance for withdrawal', 'INSUFFICIENT_BALANCE');
      }

      const payoutRequest = await tx.payoutRequest.create({
        data: {
          userId: user.id,
          rail: body.rail,
          destination: body.destination,
          amount: withdrawalAmount,
          currency: user.currency,
          status: PayoutStatus.PENDING,
        },
      });

      const ledgerEntry = await tx.ledgerEntry.create({
        data: {
          userId: user.id,
          type: LedgerEntryType.PAYOUT_WITHDRAWAL,
          amount: withdrawalAmount.neg(),
          currency: user.currency,
          referenceId: payoutRequest.id,
        },
      });

      const updatedUser = await tx.user.findUnique({
        where: { id: user.id },
      });

      const remainingBalance = updatedUser
        ? toDecimal(updatedUser.walletBalance)
        : toDecimal(user.walletBalance).sub(withdrawalAmount);

      return {
        payout: serializePayoutRequest(payoutRequest),
        ledgerEntryId: ledgerEntry.id,
        walletBalance: formatDecimal6(remainingBalance),
      };
    });

    return reply.code(201).send(result);
  });

  app.get('/payouts', async (request, reply) => {
    const query = parseInput(listPayoutsQuerySchema, request.query);

    const user = await ctx.prisma.user.findUnique({
      where: { id: query.userId },
      select: { id: true },
    });

    if (!user) {
      throw notFound('User not found', 'USER_NOT_FOUND');
    }

    const payouts = await ctx.prisma.payoutRequest.findMany({
      where: {
        userId: query.userId,
        ...(query.status !== undefined ? { status: query.status } : {}),
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: query.limit,
    });

    return reply.code(200).send({
      payouts: payouts.map(serializePayoutRequest),
      count: payouts.length,
    });
  });
}
